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
volatile uint64_t platform_timer_ticks;

struct idt_entry {
    uint16_t offset_low;
    uint16_t selector;
    uint8_t ist;
    uint8_t attributes;
    uint16_t offset_middle;
    uint32_t offset_high;
    uint32_t reserved;
} __attribute__((packed));

struct idt_pointer {
    uint16_t limit;
    uint64_t address;
} __attribute__((packed));

static struct idt_entry idt[256] __attribute__((aligned(16)));

extern void platform_timer_interrupt_entry(void);

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

static inline uint8_t port_in8(uint16_t port) {
    uint8_t value;
    __asm__ volatile("inb %1, %0" : "=a"(value) : "Nd"(port));
    return value;
}

static inline void port_out8(uint16_t port, uint8_t value) {
    __asm__ volatile("outb %0, %1" : : "a"(value), "Nd"(port));
}

static inline void io_wait(void) {
    port_out8(0x80, 0);
}

static void idt_set_handler(uint8_t vector, void (*handler)(void)) {
    uint64_t address = (uint64_t)handler;
    uint16_t code_selector;
    __asm__ volatile("mov %%cs, %0" : "=r"(code_selector));
    idt[vector].offset_low = (uint16_t)address;
    idt[vector].selector = code_selector;
    idt[vector].ist = 0;
    idt[vector].attributes = 0x8e;
    idt[vector].offset_middle = (uint16_t)(address >> 16);
    idt[vector].offset_high = (uint32_t)(address >> 32);
    idt[vector].reserved = 0;
}

static void pic_remap_and_mask(void) {
    port_out8(0x20, 0x11);
    io_wait();
    port_out8(0xa0, 0x11);
    io_wait();
    port_out8(0x21, 0x20);
    io_wait();
    port_out8(0xa1, 0x28);
    io_wait();
    port_out8(0x21, 0x04);
    io_wait();
    port_out8(0xa1, 0x02);
    io_wait();
    port_out8(0x21, 0x01);
    io_wait();
    port_out8(0xa1, 0x01);
    io_wait();

    port_out8(0x21, 0xfe);
    port_out8(0xa1, 0xff);
}

void platform_init_timer(void) {
    const uint16_t divisor = 1193; // ~= 1000 Hz
    platform_timer_ticks = 0;
    idt_set_handler(32, platform_timer_interrupt_entry);

    struct idt_pointer pointer = {
        .limit = (uint16_t)(sizeof(idt) - 1),
        .address = (uint64_t)idt,
    };
    __asm__ volatile("lidt %0" : : "m"(pointer));

    pic_remap_and_mask();
    port_out8(0x43, 0x36);
    port_out8(0x40, (uint8_t)(divisor & 0xff));
    port_out8(0x40, (uint8_t)(divisor >> 8));
}

void platform_delay(uint64_t milliseconds) {
    uint64_t deadline = platform_timer_ticks + milliseconds;
    while ((int64_t)(platform_timer_ticks - deadline) < 0) {
        __asm__ volatile("sti; hlt; cli" ::: "memory");
    }
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
