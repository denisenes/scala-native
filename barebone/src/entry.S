.section .text

.global kernel_main
.type kernel_main, @function

.extern platform_init
.extern kmain

.extern report_kmain_ok
.extern report_kmain_bad

kernel_main:
    cli
    andq $-16, %rsp

    call platform_init

    call kmain

    movl %eax, %ebx

    cmpl $43, %ebx
    jne .bad

    movl %ebx, %edi
    call report_kmain_ok
    jmp .halt

    .bad:
    movl %ebx, %edi
    call report_kmain_bad

.halt:
    hlt
    jmp .halt