#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "limine.h"

#if defined(__linux__)
#error "You are not using a cross-compiler"
#endif

#if !defined(__x86_64__)
#error "This kernel requires x86_64"
#endif

enum vga_color {
    VGA_COLOR_BLACK = 0,
    VGA_COLOR_BLUE = 1,
    VGA_COLOR_GREEN = 2,
    VGA_COLOR_CYAN = 3,
    VGA_COLOR_RED = 4,
    VGA_COLOR_MAGENTA = 5,
    VGA_COLOR_BROWN = 6,
    VGA_COLOR_LIGHT_GREY = 7,
    VGA_COLOR_DARK_GREY = 8,
    VGA_COLOR_LIGHT_BLUE = 9,
    VGA_COLOR_LIGHT_GREEN = 10,
    VGA_COLOR_LIGHT_CYAN = 11,
    VGA_COLOR_LIGHT_RED = 12,
    VGA_COLOR_LIGHT_MAGENTA = 13,
    VGA_COLOR_LIGHT_BROWN = 14,
    VGA_COLOR_WHITE = 15,
};

static volatile struct limine_framebuffer_request framebuffer_request = {
    .id = LIMINE_FRAMEBUFFER_REQUEST_ID,
    .revision = 0,
    .response = NULL
};

static struct limine_framebuffer *framebuffer;

static const uint32_t colors[16] = {
    0x000000,
    0x0000AA,
    0x00AA00,
    0x00AAAA,
    0xAA0000,
    0xAA00AA,
    0xAA5500,
    0xAAAAAA,
    0x555555,
    0x5555FF,
    0x55FF55,
    0x55FFFF,
    0xFF5555,
    0xFF55FF,
    0xFFFF55,
    0xFFFFFF
};

size_t terminal_row;
size_t terminal_column;
uint8_t terminal_color;

#define FONT_WIDTH 8
#define FONT_HEIGHT 8


static const uint8_t font[128][8] = {
    [' '] = {
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00
    },

    ['!'] = {
        0x18, 0x18, 0x18, 0x18,
        0x18, 0x00, 0x18, 0x00
    },

    [','] = {
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x18, 0x18, 0x30
    },

    ['H'] = {
        0x66, 0x66, 0x66, 0x7E,
        0x66, 0x66, 0x66, 0x00
    },

    ['W'] = {
        0x63, 0x63, 0x63, 0x6B,
        0x7F, 0x77, 0x63, 0x00
    },

    ['d'] = {
        0x06, 0x06, 0x3E, 0x66,
        0x66, 0x66, 0x3E, 0x00
    },

    ['e'] = {
        0x00, 0x00, 0x3C, 0x66,
        0x7E, 0x60, 0x3C, 0x00
    },

    ['k'] = {
        0x60, 0x60, 0x66, 0x6C,
        0x78, 0x6C, 0x66, 0x00
    },

    ['l'] = {
        0x38, 0x18, 0x18, 0x18,
        0x18, 0x18, 0x3C, 0x00
    },

    ['n'] = {
        0x00, 0x00, 0x7C, 0x66,
        0x66, 0x66, 0x66, 0x00
    },

    ['o'] = {
        0x00, 0x00, 0x3C, 0x66,
        0x66, 0x66, 0x3C, 0x00
    },

    ['r'] = {
        0x00, 0x00, 0x6C, 0x76,
        0x60, 0x60, 0x60, 0x00
    },
};

size_t strlen(const char *str)
{
    size_t len = 0;

    while (str[len])
        len++;

    return len;
}

static void putpixel(size_t x, size_t y, uint32_t color)
{
    volatile uint32_t *row =
        (volatile uint32_t *)((uintptr_t)framebuffer->address
        + y * framebuffer->pitch);

    row[x] = color;
}

static void draw_char(
    char c,
    size_t x,
    size_t y,
    uint32_t fg,
    uint32_t bg)
{
    const uint8_t *glyph = font[(unsigned char)c];

    for (size_t row = 0; row < FONT_HEIGHT; row++) {
        for (size_t col = 0; col < FONT_WIDTH; col++) {
            bool set = glyph[row] & (1 << (7 - col));

            putpixel(
                x + col,
                y + row,
                set ? fg : bg
            );
        }
    }
}

void terminal_initialize(void)
{
    terminal_row = 0;
    terminal_column = 0;

    terminal_color =
        VGA_COLOR_LIGHT_GREY |
        (VGA_COLOR_BLACK << 4);

    uint32_t background = colors[VGA_COLOR_BLACK];

    for (size_t y = 0; y < framebuffer->height; y++) {
        for (size_t x = 0; x < framebuffer->width; x++) {
            putpixel(x, y, background);
        }
    }
}

void terminal_setcolor(uint8_t color)
{
    terminal_color = color;
}

void terminal_putentryat(
    char c,
    uint8_t color,
    size_t x,
    size_t y)
{
    uint8_t fg_index = color & 0x0F;
    uint8_t bg_index = (color >> 4) & 0x0F;

    draw_char(
        c,
        x * FONT_WIDTH,
        y * FONT_HEIGHT,
        colors[fg_index],
        colors[bg_index]
    );
}

void terminal_putchar(char c)
{
    if (c == '\n') {
        terminal_column = 0;
        terminal_row++;
        return;
    }

    terminal_putentryat(
        c,
        terminal_color,
        terminal_column,
        terminal_row
    );

    terminal_column++;

    size_t terminal_width =
        framebuffer->width / FONT_WIDTH;

    size_t terminal_height =
        framebuffer->height / FONT_HEIGHT;

    if (terminal_column >= terminal_width) {
        terminal_column = 0;
        terminal_row++;
    }

    if (terminal_row >= terminal_height) {
        terminal_row = 0;
    }
}

void terminal_write(const char *data, size_t size)
{
    for (size_t i = 0; i < size; i++)
        terminal_putchar(data[i]);
}

void terminal_writestring(const char *data)
{
    terminal_write(data, strlen(data));
}

// void kernel_main(void)
// {
//     if (framebuffer_request.response == NULL ||
//         framebuffer_request.response->framebuffer_count < 1) {
//         for (;;)
//             __asm__ volatile ("hlt");
//     }

//     framebuffer =
//         framebuffer_request.response->framebuffers[0];

//     terminal_initialize();

//     terminal_writestring("Hello, kernel World!\n");

//     for (;;) {
//         __asm__ volatile ("hlt");
//     }
//}

void platform_init(void)
{
    if (framebuffer_request.response == NULL ||
        framebuffer_request.response->framebuffer_count < 1) {
        for (;;) {
            __asm__ volatile ("hlt");
        }
    }

    framebuffer =
        framebuffer_request.response->framebuffers[0];


    terminal_initialize();

    terminal_writestring("Hello\n");
}

void report_kmain_ok(int result)
{
    terminal_writestring("ok\n");
    //terminal_write_int(result);
}

void report_kmain_bad(int result)
{
    terminal_writestring("nok\n");
    //terminal_write_int(result);
}