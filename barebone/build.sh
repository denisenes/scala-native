#!/usr/bin/env bash

set -euo pipefail

OS_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$OS_DIR/src"
BUILD_DIR="$OS_DIR/build"
LIMINE_DIR="$SRC_DIR/limine-binary"

case "${1:-}" in
    ""|--no-run) ;;
    *) echo "Usage: $0 [--no-run]" >&2; exit 1 ;;
esac

mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

if [[ ! -f megaobj.o ]]; then
    echo "Missing $BUILD_DIR/megaobj.o: copy the Scala Native object here first." >&2
    exit 1
fi

# Build the host utility in build, keeping the dependency sources untouched.
mkdir -p "$BUILD_DIR/limine-binary"
cp "$LIMINE_DIR/Makefile" "$LIMINE_DIR/limine.c" \
    "$LIMINE_DIR/limine-bios-hdd.h" "$BUILD_DIR/limine-binary/"
make -C "$BUILD_DIR/limine-binary"

# 1. kernel.c
clang \
    --target=x86_64-unknown-none-elf \
    -c "$SRC_DIR/kernel.c" \
    -o kernel.o \
    -std=gnu11 \
    -ffreestanding \
    -fno-stack-protector \
    -fno-stack-check \
    -fno-pic \
    -m64 \
    -march=x86-64 \
    -mabi=sysv \
    -mno-red-zone \
    -mcmodel=kernel \
    -O2 \
    -Wall \
    -Wextra

# 2. entry.S
clang \
    --target=x86_64-unknown-none-elf \
    -c "$SRC_DIR/entry.S" \
    -o entry.o \
    -ffreestanding \
    -fno-pic \
    -m64 \
    -mno-red-zone \
    -mcmodel=kernel

# 3. stubs.c
clang \
    --target=x86_64-unknown-none-elf \
    -c "$SRC_DIR/stubs.c" \
    -o stubs.o \
    -ffreestanding \
    -fno-builtin \
    -fno-stack-protector \
    -fno-pic \
    -m64 \
    -mno-red-zone \
    -mcmodel=kernel \
    -O2

# 4. Link kernel + Scala Native object
ld.lld \
    -m elf_x86_64 \
    -nostdlib \
    -static \
    -z max-page-size=0x1000 \
    -T "$SRC_DIR/linker.ld" \
    entry.o \
    kernel.o \
    stubs.o \
    megaobj.o \
    -o myos

# Optional sanity checks
echo "=== Undefined symbols ==="
nm -u myos || true

echo "=== kmain symbols ==="
nm myos | grep kmain || true

echo "=== kernel_main ==="
nm myos | grep kernel_main || true

# 5. Prepare the ISO tree from sources and the Limine distribution.
mkdir -p iso_root/boot/limine iso_root/EFI/BOOT
cp myos iso_root/boot/myos
cp "$SRC_DIR/limine.conf" iso_root/boot/limine/limine.conf
cp "$LIMINE_DIR/limine-bios.sys" \
    "$LIMINE_DIR/limine-bios-cd.bin" \
    "$LIMINE_DIR/limine-uefi-cd.bin" iso_root/boot/limine/
cp "$LIMINE_DIR/BOOTX64.EFI" iso_root/EFI/BOOT/BOOTX64.EFI

# 6. Build ISO
xorriso -as mkisofs \
    -R -r -J \
    -b boot/limine/limine-bios-cd.bin \
    -no-emul-boot \
    -boot-load-size 4 \
    -boot-info-table \
    -hfsplus \
    -apm-block-size 2048 \
    --efi-boot boot/limine/limine-uefi-cd.bin \
    -efi-boot-part \
    --efi-boot-image \
    --protective-msdos-label \
    iso_root \
    -o myos.iso

# 7. Install Limine BIOS stage
./limine-binary/limine bios-install myos.iso

# 8. Run QEMU unless only a build was requested.
if [[ "${1:-}" == --no-run ]]; then
    exit 0
fi

qemu-system-x86_64 \
    -cdrom myos.iso \
    -serial stdio \
    -no-reboot \
    -no-shutdown
