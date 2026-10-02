.section .text

.global kernel_main
.type kernel_main, @function

.extern platform_init
.extern kmain
.extern platform_terminate

kernel_main:
    cli
    andq $-16, %rsp

    call platform_init

    // Scala entrypoint
    call kmain

    movq %rax, %rdi
    call platform_terminate
.halt:
    hlt
    jmp .halt