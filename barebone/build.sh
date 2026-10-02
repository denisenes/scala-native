#!/usr/bin/env bash

set -euo pipefail

OS_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$OS_DIR")"
SRC_DIR="$OS_DIR/src"
BUILD_DIR="$OS_DIR/build"
LIMINE_DIR="$SRC_DIR/boot/limine-binary"
FONT_ARCHIVE="$OS_DIR/assets/Uni3-Terminus16.psf.gz"

# Scala Native build output of the sandbox project (sandbox3, Scala 3.9.0).
SCALA_OBJ_DIR="$REPO_DIR/target/out/native0.5/scala-3.9.0/sandbox/native/generated"

RUN_QEMU=1
WITH_SBT=1
# Extra arguments appended to the qemu-system-x86_64 invocation,
# e.g. --qemu-extra-args="-s -S" to wait for a gdb connection.
QEMU_EXTRA_ARGS=()
usage() {
    echo "Usage: $0 [--no-run] [--no-sbt] [--qemu-extra-args=\"<args>\"]" >&2
    exit 1
}
while (( $# > 0 )); do
    case "$1" in
        --no-run) RUN_QEMU=0 ;;
        --no-sbt) WITH_SBT=0 ;;
        --qemu-extra-args=*)
            read -r -a QEMU_EXTRA_ARGS <<< "${1#*=}" ;;
        --qemu-extra-args)
            shift
            (( $# > 0 )) || usage
            read -r -a QEMU_EXTRA_ARGS <<< "$1" ;;
        *) usage ;;
    esac
    shift
done

# ---------------------------------------------------------------------------
# 1. Compile the Scala sandbox to native objects with sbt.
#    NOTE: the sbt-side link step is intentionally broken (see the
#    "break linking" commits under tools/), so `sbt sandbox3/run` fails
#    after producing the *.ll.o codegen artifacts. That error is expected.
#    `sbt clean` is run first so the codegen always regenerates the objects.
# ---------------------------------------------------------------------------
if [[ "$WITH_SBT" == 1 ]]; then
    echo "=== Compiling Scala sandbox (sbt clean && sbt sandbox3/run) ==="
    (cd "$REPO_DIR" && sbt clean)
    # Include scala kernel part into sandbox compilation set 
    cp -r "$SRC_DIR/kernel/scala" "$REPO_DIR/sandbox/src/main/scala/kernel"
    # Build all
    if ! (cd "$REPO_DIR" && sbt sandbox3/run); then
        echo "sbt sandbox3/run failed (expected: linking is intentionally broken), continuing"
    fi
    rm -r "$REPO_DIR/sandbox/src/main/scala/kernel"
fi

shopt -s nullglob
SCALA_OBJS=("$SCALA_OBJ_DIR"/*.ll.o)
shopt -u nullglob
if (( ${#SCALA_OBJS[@]} == 0 )); then
    echo "Error: no *.ll.o objects found in $SCALA_OBJ_DIR" >&2
    echo "Did the sbt codegen step succeed?" >&2
    exit 1
fi
echo "=== Collected ${#SCALA_OBJS[@]} Scala Native objects ==="

mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

if [[ ! -f "$FONT_ARCHIVE" ]]; then
    echo "Error: console font not found: $FONT_ARCHIVE" >&2
    exit 1
fi

# Embed the PSF1 console font as a read-only object file.
gzip -dc "$FONT_ARCHIVE" > font.psf
objcopy \
    --input-target=binary \
    --output-target=elf64-x86-64 \
    --binary-architecture=i386:x86-64 \
    --rename-section .data=.rodata.font,alloc,load,readonly,data,contents \
    font.psf font.o

# ---------------------------------------------------------------------------
# 2. Merge all Scala Native objects into a single relocatable object file.
# ---------------------------------------------------------------------------
echo "=== Building megaobj.o ==="
clang -no-pie -Wl,-r -nostdlib -o megaobj.o "${SCALA_OBJS[@]}"

# Helper: megaobj disasm
objdump -d -r megaobj.o > megaobj.asm

# Build the host utility in build, keeping the dependency sources untouched.
echo "=== Building limine-binary ==="
mkdir -p "$BUILD_DIR/limine-binary"
cp "$LIMINE_DIR/Makefile" "$LIMINE_DIR/limine.c" "$LIMINE_DIR/limine-bios-hdd.h" "$BUILD_DIR/limine-binary/"
make -C "$BUILD_DIR/limine-binary"

echo "=== Compile entry.s ==="
clang \
    --target=x86_64-unknown-none-elf \
    -c "$SRC_DIR/kernel/asm/entry.S" \
    -o entry.o \
    -ffreestanding \
    -fno-pic \
    -m64 \
    -mno-red-zone \
    -mcmodel=kernel

echo "=== Compile platfrom.c ==="
clang \
    --target=x86_64-unknown-none-elf \
    -I "$SRC_DIR/boot/" \
    -c "$SRC_DIR/kernel/c/platform.c" \
    -o platform.o \
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

echo "=== Compile stubs.c ==="
clang \
    --target=x86_64-unknown-none-elf \
    -c "$SRC_DIR/kernel/c/stubs.c" \
    -o stubs.o \
    -ffreestanding \
    -fno-builtin \
    -fno-stack-protector \
    -fno-pic \
    -m64 \
    -mno-red-zone \
    -mcmodel=kernel \
    -O2

echo "=== Linking... ==="
ld.lld \
    -m elf_x86_64 \
    -nostdlib \
    -static \
    -z max-page-size=0x1000 \
    -T "$SRC_DIR/linker.ld" \
    entry.o \
    platform.o \
    font.o \
    stubs.o \
    megaobj.o \
    -o myos

echo "=== Undefined symbols ==="
nm -u myos || true

echo "=== kmain symbols ==="
nm myos | grep kmain || true

echo "=== kernel_main ==="
nm myos | grep kernel_main || true

mkdir -p iso_root/boot/limine iso_root/EFI/BOOT
cp myos iso_root/boot/myos
cp "$SRC_DIR/boot/limine.conf" iso_root/boot/limine/limine.conf
cp "$LIMINE_DIR/limine-bios.sys" \
    "$LIMINE_DIR/limine-bios-cd.bin" \
    "$LIMINE_DIR/limine-uefi-cd.bin" iso_root/boot/limine/
cp "$LIMINE_DIR/BOOTX64.EFI" iso_root/EFI/BOOT/BOOTX64.EFI

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

./limine-binary/limine bios-install myos.iso

if [[ "$RUN_QEMU" == 0 ]]; then
    exit 0
fi

qemu-system-x86_64 \
    -cdrom myos.iso \
    -serial stdio \
    -no-reboot \
    -no-shutdown \
    -m 1G \
    "${QEMU_EXTRA_ARGS[@]}"
