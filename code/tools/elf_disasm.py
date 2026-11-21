#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
小工具：解析 ELF（ARM/Thumb），列导出符号、反汇编指定函数，并把 PLT 调用解析成导入名。

依赖：
    pip install pyelftools capstone

用法：
    python3 elf_disasm.py liblcd.so                 # 列导出符号 + .plt
    python3 elf_disasm.py liblcd.so lcd_transfer_stream
    python3 elf_disasm.py liblcd.so 0x15b8          # 按地址反汇编（Thumb）

说明：
    * 函数大小取自符号表；若为 0（被 strip 或无 .symtab），默认反汇编 256 字节。
    * ARM/Thumb 依符号地址最低位判断；按地址手工指定时默认 Thumb，可用 --arm 覆盖。
"""
import sys
import struct

from elftools.elf.elffile import ELFFile
from capstone import (
    Cs, CS_ARCH_ARM, CS_MODE_ARM, CS_MODE_THUMB, ARM_OP_IMM,
)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    path = sys.argv[1]
    targets = sys.argv[2:]
    arm_mode = "--arm" in targets
    targets = [t for t in targets if t != "--arm"]

    elf = ELFFile(open(path, "rb"))
    dyn = elf.get_section_by_name(".dynsym")
    text = elf.get_section_by_name(".text")
    if text is None:
        sys.exit("no .text")
    tdata = text.data()
    tbase = text["sh_addr"]

    syms = {}
    if dyn is not None:
        for s in dyn.iter_symbols():
            if s["st_value"]:
                syms[s.name] = (s["st_value"], s["st_size"], s["st_info"]["type"])

    # PLT 映射（ARM: 入口 12 字节，起始位置视链接器而定；这里按顺序给出）
    plt = elf.get_section_by_name(".plt")
    rel = elf.get_section_by_name(".rel.plt")
    names = []
    if rel is not None and dyn is not None:
        names = [dyn.get_symbol(r["r_info_sym"]).name for r in rel.iter_relocations()]
    plt_map = {}
    if plt is not None:
        pbase = plt["sh_addr"]
        # 常见布局：.plt 起始 + 0x14 为第 0 个入口，步长 12
        for i, n in enumerate(names):
            for base_off in (0x14, 0x10, 0x0):
                plt_map[pbase + base_off + 12 * i] = n

    print("== exported symbols ==")
    for n, (v, sz, t) in sorted(syms.items(), key=lambda x: x[1][0]):
        print("  %08x  %-32s %s size=%d" % (v & ~1, n, t, sz))

    if plt is not None:
        print("\n== .plt ==")
        md = Cs(CS_ARCH_ARM, CS_MODE_ARM)
        for ins in md.disasm(plt.data(), plt["sh_addr"]):
            print("  %08x: %-8s %s" % (ins.address, ins.mnemonic, ins.op_str))

    def resolve_name(tgt):
        if tgt in plt_map:
            return "PLT:" + plt_map[tgt]
        for n, (v, _, _) in syms.items():
            if (v & ~1) == tgt:
                return n
        return None

    def dump(addr, size, label):
        off = addr - tbase
        code = tdata[off:off + (size or 256)]
        md = Cs(CS_ARCH_ARM, CS_MODE_ARM if arm_mode else CS_MODE_THUMB)
        md.detail = True
        print("\n== %s @%08x ==" % (label, addr))
        for ins in md.disasm(code, addr):
            extra = ""
            if ins.mnemonic in ("bl", "blx", "b") and ins.operands and \
                    ins.operands[0].type == ARM_OP_IMM:
                tgt = ins.operands[0].imm
                nm = resolve_name(tgt)
                if nm:
                    extra = "  -> " + nm
            print("  %08x: %-8s %-26s%s" % (ins.address, ins.mnemonic, ins.op_str, extra))

    if not targets:
        return
    for t in targets:
        if t in syms:
            v, sz, typ = syms[t]
            dump(v & ~1, sz, t)
        else:
            try:
                dump(int(t, 16), 0, t)
            except ValueError:
                print("!! unknown target:", t)


if __name__ == "__main__":
    main()
