#!/usr/bin/env bash

set -euo pipefail

OS_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$OS_DIR")"
SRC_DIR="$OS_DIR/src"
BUILD_DIR="$OS_DIR/build"
LIMINE_DIR="$SRC_DIR/limine-binary"

# Scala Native build output of the sandbox project (sandbox3, Scala 3.9.0).
SCALA_OBJ_DIR="$REPO_DIR/target/out/native0.5/scala-3.9.0/sandbox/native/generated"

RUN_QEMU=1
WITH_SBT=1
for arg in "$@"; do
    case "$arg" in
        --no-run) RUN_QEMU=0 ;;
        --no-sbt) WITH_SBT=0 ;;
        *) echo "Usage: $0 [--no-run] [--no-sbt]" >&2; exit 1 ;;
    esac
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
    if ! (cd "$REPO_DIR" && sbt sandbox3/run); then
        echo "sbt sandbox3/run failed (expected: linking is intentionally broken), continuing"
    fi
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

# ---------------------------------------------------------------------------
# 2. Merge all Scala Native objects into a single relocatable object file.
# ---------------------------------------------------------------------------
echo "=== Building megaobj.o ==="
clang -no-pie -Wl,-r -nostdlib -o megaobj.o "${SCALA_OBJS[@]}"

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
if [[ "$RUN_QEMU" == 0 ]]; then
    exit 0
fi

qemu-system-x86_64 \
    -cdrom myos.iso \
    -serial stdio \
    -no-reboot \
    -no-shutdown
