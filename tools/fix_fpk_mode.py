"""修正 fpk 内 cmd/ 脚本的可执行权限。

坑：Windows 文件系统没有 exec 位，fnpack 打包时会把 cmd/* 记成 0666，
装到飞牛上脚本跑不起来（表现为应用一直显示"未运行"）。
这个脚本把 fpk（本质是 tar.gz）里的 cmd/* 和目录重打包成 0755。

用法：
    python tools/fix_fpk_mode.py                 # 处理仓库根目录的 photovault.fpk
    python tools/fix_fpk_mode.py path/to/x.fpk
"""
import io
import os
import shutil
import sys
import tarfile

DEFAULT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "photovault.fpk"
)


def main() -> None:
    fpk = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
    if not os.path.exists(fpk):
        print(f"找不到 {fpk}")
        sys.exit(1)

    backup = fpk + ".bak"
    shutil.copy2(fpk, backup)

    with tarfile.open(backup, "r:gz") as src:
        with tarfile.open(fpk, "w:gz") as dst:
            for m in src.getmembers():
                if m.name.startswith("cmd/") and m.isfile():
                    m.mode = 0o755
                if m.isdir():
                    m.mode = 0o755
                dst.addfile(m, src.extractfile(m))

    os.remove(backup)

    with tarfile.open(fpk, "r:gz") as chk:
        for m in chk.getmembers():
            if m.name.startswith("cmd/"):
                print(f"  {oct(m.mode)}  {m.name}")
    print(f"完成：{fpk}（{os.path.getsize(fpk)} 字节）")


if __name__ == "__main__":
    main()
