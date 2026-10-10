// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: isochronous USB audio capture straight from usbfs. Android's public USB API has no
// isochronous transfers, so this submits iso URBs on the file descriptor UsbManager already handed
// the app and hands the PCM packets to Kotlin through a ring buffer. No audio HAL, no AudioRecord.
#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define RING_BYTES (512 * 1024)
#define MAX_URBS 16
#define MAX_PACKETS 128

struct capture {
    int fd;
    int interface_number;
    int alt_setting;
    int endpoint;
    int max_packet;
    int packets_per_urb;
    int urb_count;
    struct usbdevfs_urb *urbs[MAX_URBS];
    unsigned char *buffers[MAX_URBS];
    pthread_t reaper;
    int reaper_started;
    volatile int stopping;
    volatile int failed_errno;
    pthread_mutex_t lock;
    pthread_cond_t cond;
    unsigned char ring[RING_BYTES];
    size_t head, tail, count;
    uint64_t packets, bytes, errors, overruns, short_packets, urbs_reaped;
};

static void throw_io(JNIEnv *env, const char *operation, int error_number) {
    char message[200];
    snprintf(message, sizeof(message), "%s failed: errno=%d (%s)", operation, error_number, strerror(error_number));
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, message);
}

static void ring_push(struct capture *c, const unsigned char *data, size_t length) {
    pthread_mutex_lock(&c->lock);
    if (length > RING_BYTES) { data += length - RING_BYTES; length = RING_BYTES; }
    if (c->count + length > RING_BYTES) {
        size_t drop = c->count + length - RING_BYTES;
        c->head = (c->head + drop) % RING_BYTES;
        c->count -= drop;
        c->overruns++;
    }
    size_t first = RING_BYTES - c->tail;
    if (first > length) first = length;
    memcpy(c->ring + c->tail, data, first);
    if (length > first) memcpy(c->ring, data + first, length - first);
    c->tail = (c->tail + length) % RING_BYTES;
    c->count += length;
    pthread_cond_signal(&c->cond);
    pthread_mutex_unlock(&c->lock);
}

static int submit(struct capture *c, int index) {
    struct usbdevfs_urb *urb = c->urbs[index];
    for (int i = 0; i < c->packets_per_urb; i++) {
        urb->iso_frame_desc[i].length = (unsigned int) c->max_packet;
        urb->iso_frame_desc[i].actual_length = 0;
        urb->iso_frame_desc[i].status = 0;
    }
    urb->actual_length = 0;
    urb->status = 0;
    urb->error_count = 0;
    return ioctl(c->fd, USBDEVFS_SUBMITURB, urb);
}

static void *reaper_main(void *arg) {
    struct capture *c = (struct capture *) arg;
    while (!c->stopping) {
        struct usbdevfs_urb *urb = NULL;
        if (ioctl(c->fd, USBDEVFS_REAPURB, &urb) < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            if (!c->stopping) c->failed_errno = errno;
            break;
        }
        if (urb == NULL) continue;
        int index = (int) (intptr_t) urb->usercontext;
        c->urbs_reaped++;
        if (!c->stopping) {
            unsigned char *base = c->buffers[index];
            for (int i = 0; i < c->packets_per_urb; i++) {
                struct usbdevfs_iso_packet_desc *p = &urb->iso_frame_desc[i];
                if (p->status != 0) { c->errors++; continue; }
                if (p->actual_length == 0) { c->short_packets++; continue; }
                ring_push(c, base + (size_t) i * c->max_packet, p->actual_length);
                c->packets++;
                c->bytes += p->actual_length;
            }
            if (submit(c, index) < 0) {
                if (!c->stopping) { c->failed_errno = errno; break; }
            }
        }
    }
    pthread_mutex_lock(&c->lock);
    pthread_cond_broadcast(&c->cond);
    pthread_mutex_unlock(&c->lock);
    return NULL;
}

static void destroy(struct capture *c) {
    c->stopping = 1;
    for (int i = 0; i < c->urb_count; i++) {
        if (c->urbs[i] != NULL) ioctl(c->fd, USBDEVFS_DISCARDURB, c->urbs[i]);
    }
    if (c->reaper_started) {
        pthread_join(c->reaper, NULL);
        c->reaper_started = 0;
    }
    // Anything still in flight after the join was discarded; reap without blocking so the
    // kernel does not keep references to buffers that are about to be freed.
    for (int i = 0; i < c->urb_count * 2; i++) {
        struct usbdevfs_urb *urb = NULL;
        if (ioctl(c->fd, USBDEVFS_REAPURBNDELAY, &urb) < 0) break;
    }
    struct usbdevfs_setinterface idle = { .interface = (unsigned int) c->interface_number, .altsetting = 0 };
    ioctl(c->fd, USBDEVFS_SETINTERFACE, &idle);
    for (int i = 0; i < c->urb_count; i++) {
        free(c->urbs[i]); c->urbs[i] = NULL;
        free(c->buffers[i]); c->buffers[i] = NULL;
    }
    pthread_mutex_destroy(&c->lock);
    pthread_cond_destroy(&c->cond);
    free(c);
}

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_ipod_UsbIsoAudioNative_open(
        JNIEnv *env, jobject receiver, jint fd, jint interface_number, jint alt_setting, jint endpoint,
        jint max_packet, jint packets_per_urb, jint urb_count) {
    (void) receiver;
    if (packets_per_urb < 1 || packets_per_urb > MAX_PACKETS || urb_count < 1 || urb_count > MAX_URBS || max_packet < 1 || max_packet > 3072) {
        throw_io(env, "iso capture parameters", EINVAL);
        return 0;
    }
    struct capture *c = calloc(1, sizeof(struct capture));
    if (c == NULL) { throw_io(env, "iso capture allocation", ENOMEM); return 0; }
    c->fd = fd;
    c->interface_number = interface_number;
    c->alt_setting = alt_setting;
    c->endpoint = endpoint;
    c->max_packet = max_packet;
    c->packets_per_urb = packets_per_urb;
    c->urb_count = urb_count;
    pthread_mutex_init(&c->lock, NULL);
    pthread_condattr_t attr;
    pthread_condattr_init(&attr);
    pthread_condattr_setclock(&attr, CLOCK_MONOTONIC);
    pthread_cond_init(&c->cond, &attr);
    pthread_condattr_destroy(&attr);

    struct usbdevfs_setinterface alt = { .interface = (unsigned int) interface_number, .altsetting = (unsigned int) alt_setting };
    if (ioctl(fd, USBDEVFS_SETINTERFACE, &alt) < 0) {
        int saved = errno;
        throw_io(env, "USBDEVFS_SETINTERFACE", saved);
        pthread_mutex_destroy(&c->lock); pthread_cond_destroy(&c->cond); free(c);
        return 0;
    }
    for (int i = 0; i < urb_count; i++) {
        size_t urb_bytes = sizeof(struct usbdevfs_urb) + (size_t) packets_per_urb * sizeof(struct usbdevfs_iso_packet_desc);
        struct usbdevfs_urb *urb = calloc(1, urb_bytes);
        unsigned char *buffer = calloc((size_t) packets_per_urb, (size_t) max_packet);
        if (urb == NULL || buffer == NULL) {
            free(urb); free(buffer);
            c->urb_count = i;
            destroy(c);
            throw_io(env, "iso URB allocation", ENOMEM);
            return 0;
        }
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = (unsigned char) endpoint;
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->buffer = buffer;
        urb->buffer_length = packets_per_urb * max_packet;
        urb->number_of_packets = packets_per_urb;
        urb->usercontext = (void *) (intptr_t) i;
        c->urbs[i] = urb;
        c->buffers[i] = buffer;
    }
    for (int i = 0; i < urb_count; i++) {
        if (submit(c, i) < 0) {
            int saved = errno;
            destroy(c);
            throw_io(env, "USBDEVFS_SUBMITURB (iso)", saved);
            return 0;
        }
    }
    if (pthread_create(&c->reaper, NULL, reaper_main, c) != 0) {
        int saved = errno;
        destroy(c);
        throw_io(env, "iso reaper thread", saved);
        return 0;
    }
    c->reaper_started = 1;
    return (jlong) (intptr_t) c;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_ipod_UsbIsoAudioNative_read(
        JNIEnv *env, jobject receiver, jlong handle, jbyteArray target, jint offset, jint length, jint timeout_millis) {
    (void) receiver;
    struct capture *c = (struct capture *) (intptr_t) handle;
    if (c == NULL || length <= 0) return 0;
    struct timespec deadline;
    clock_gettime(CLOCK_MONOTONIC, &deadline);
    deadline.tv_sec += timeout_millis / 1000;
    deadline.tv_nsec += (long) (timeout_millis % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) { deadline.tv_sec++; deadline.tv_nsec -= 1000000000L; }

    pthread_mutex_lock(&c->lock);
    while (c->count < (size_t) length && c->failed_errno == 0 && !c->stopping) {
        if (pthread_cond_timedwait(&c->cond, &c->lock, &deadline) == ETIMEDOUT) break;
    }
    if (c->failed_errno != 0 && c->count == 0) {
        int saved = c->failed_errno;
        pthread_mutex_unlock(&c->lock);
        throw_io(env, "isochronous capture", saved);
        return -1;
    }
    size_t take = c->count < (size_t) length ? c->count : (size_t) length;
    if (take > 0) {
        unsigned char *scratch = malloc(take);
        if (scratch == NULL) { pthread_mutex_unlock(&c->lock); throw_io(env, "iso read allocation", ENOMEM); return -1; }
        size_t first = RING_BYTES - c->head;
        if (first > take) first = take;
        memcpy(scratch, c->ring + c->head, first);
        if (take > first) memcpy(scratch + first, c->ring, take - first);
        c->head = (c->head + take) % RING_BYTES;
        c->count -= take;
        pthread_mutex_unlock(&c->lock);
        (*env)->SetByteArrayRegion(env, target, offset, (jsize) take, (const jbyte *) scratch);
        free(scratch);
        return (jint) take;
    }
    pthread_mutex_unlock(&c->lock);
    return 0;
}

JNIEXPORT jlongArray JNICALL
Java_com_shilapi_xcertplay_ipod_UsbIsoAudioNative_stats(JNIEnv *env, jobject receiver, jlong handle) {
    (void) receiver;
    struct capture *c = (struct capture *) (intptr_t) handle;
    jlongArray result = (*env)->NewLongArray(env, 7);
    if (result == NULL || c == NULL) return result;
    pthread_mutex_lock(&c->lock);
    jlong values[7] = { (jlong) c->packets, (jlong) c->bytes, (jlong) c->errors, (jlong) c->overruns,
                        (jlong) c->short_packets, (jlong) c->urbs_reaped, (jlong) c->count };
    pthread_mutex_unlock(&c->lock);
    (*env)->SetLongArrayRegion(env, result, 0, 7, values);
    return result;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_ipod_UsbIsoAudioNative_close(JNIEnv *env, jobject receiver, jlong handle) {
    (void) env; (void) receiver;
    struct capture *c = (struct capture *) (intptr_t) handle;
    if (c != NULL) destroy(c);
}
