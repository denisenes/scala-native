// GC support: keeps the callee-saved registers on the stack while the
// conservative mark phase (k_gc_mark) scans it, so they are seen as roots.

.section .text

.global dump_non_volatile_regs
.type dump_non_volatile_regs, @function
.extern k_gc_mark

dump_non_volatile_regs:
    pushq %rbp
    pushq %rbx
    pushq %r12
    pushq %r13
    pushq %r14
    pushq %r15
    subq $8, %rsp          // keep the SysV 16-byte stack alignment for the call
    call k_gc_mark
    addq $8, %rsp
    popq %r15
    popq %r14
    popq %r13
    popq %r12
    popq %rbx
    popq %rbp
    ret

.section .bss
.global __gc_stack_top
__gc_stack_top:
    .quad 0