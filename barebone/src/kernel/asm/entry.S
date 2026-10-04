.section .text

.global kernel_main
.type kernel_main, @function

.extern platform_init
.extern kmain
.extern platform_terminate
.extern platform_timer_ticks

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

.global platform_timer_interrupt_entry
.type platform_timer_interrupt_entry, @function
platform_timer_interrupt_entry:
    pushq %rax
    incq platform_timer_ticks(%rip)
    movb $0x20, %al
    outb %al, $0x20
    popq %rax
    iretq
