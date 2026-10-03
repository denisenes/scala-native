#include <stdbool.h>
#include <stdint.h>
#include "std.h"

#include "limine.h"

#if defined(__linux__)
#error "You are not using a cross-compiler"
#endif

#if !defined(__x86_64__)
#error "This kernel requires x86_64"
#endif

__attribute__((used, section(".limine_requests_start"))) 
static volatile uint64_t limine_requests_start_marker[] = LIMINE_REQUESTS_START_MARKER;

__attribute__((used, section(".limine_requests"))) 
static volatile struct limine_framebuffer_request framebuffer_request = {
    .id = LIMINE_FRAMEBUFFER_REQUEST_ID, .revision = 0, .response = 0
};

__attribute__((used, section(".limine_requests_end"))) static volatile uint64_t
limine_requests_end_marker[] = LIMINE_REQUESTS_END_MARKER;

static uint64_t terminal_state[2];
static struct limine_framebuffer *framebuffer;

extern const uint8_t _binary_font_psf_start[];
extern const uint8_t _binary_font_psf_end[];

bool platform_init_framebuffer(void) {
    if (framebuffer_request.response == 0 ||
        framebuffer_request.response->framebuffer_count == 0) {
        return false;
    }

    framebuffer = framebuffer_request.response->framebuffers[0];
    return true;
}

bool platform_check_framebuffer(void) {
    return framebuffer != NULL;
}

void *platform_framebuffer_address(void) { return framebuffer->address; }

uint64_t platform_framebuffer_width(void) { return framebuffer->width; }

uint64_t platform_framebuffer_height(void) { return framebuffer->height; }

uint64_t platform_framebuffer_pitch(void) { return framebuffer->pitch; }

const void *platform_font_address(void) { return _binary_font_psf_start; }

uint64_t platform_font_size(void) {
    return (uint64_t)(_binary_font_psf_end - _binary_font_psf_start);
}

uint64_t *platform_terminal_state(void) { return terminal_state; }

/*
 * A temporary frame delay until the kernel has a timer driver.  Keeping it on
 * the platform side makes the busy loop observable to the compiler and easy
 * to replace with a real timer interrupt later.
 */
void platform_delay(uint64_t iterations) {
    while (iterations-- != 0) {
        __asm__ volatile("pause");
    }
}

static inline uint8_t port_in8(uint16_t port) {
    uint8_t value;
    __asm__ volatile("inb %1, %0" : "=a"(value) : "Nd"(port));
    return value;
}

/* Return one PS/2 scan-code byte, or -1 when the controller has no data. */
int32_t platform_poll_key(void) {
    if ((port_in8(0x64) & 0x01) == 0) {
        return -1;
    }
    return (int32_t)port_in8(0x60);
}

__attribute__((noreturn)) 
void platform_halt(void) {
    for (;;) {
        __asm__ volatile("hlt");
    }
}
