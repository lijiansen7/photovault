"""一键准备构建安卓 App 的工具链：JDK 17 + Android SDK + Gradle。

全部装到 ~/.workbuddy/binaries 下，不污染系统环境、不需要管理员权限。
装完后 `cd android && gradlew.bat assembleDebug` 即可出 APK。

用法：
    python tools/setup_android_sdk.py
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID_DIR = ROOT / "android"
BIN = Path.home() / ".workbuddy" / "binaries"

JDK_DIR = BIN / "jdk17"
SDK_DIR = BIN / "android-sdk"
GRADLE_VER = "8.7"
GRADLE_DIR = BIN / f"gradle-{GRADLE_VER}"

# 全部选国内可直连的源；services.gradle.org / GitHub 在部分网络下不通
URLS = {
    "jdk17.zip": "https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip",
    "gradle.zip": f"https://mirrors.cloud.tencent.com/gradle/gradle-{GRADLE_VER}-bin.zip",
    "clt.zip": "https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip",
}

LICENSES = {
    "android-sdk-license": [
        "8933bad161af4178b1185d1a37fbf41ea5269c55",
        "d56f5187479451eabf01fb78af6dfcb131a6481e",
        "24333f8a63b6825ea9c5514f83c2829b004d1fee",
    ],
    "android-sdk-preview-license": ["84831b9409646a918e30573bab4c9c91346d8abd"],
}


def log(msg: str) -> None:
    print(msg, flush=True)


def download(url: str, dest: Path) -> Path:
    if dest.exists() and dest.stat().st_size > 1024:
        log(f"  (已缓存) {dest.name} {dest.stat().st_size // 1048576}MB")
        return dest
    log(f"  下载 {url}")
    tmp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if total:
                print(f"\r    {done * 100 // total}%  {done // 1048576}/{total // 1048576}MB", end="")
    print()
    tmp.rename(dest)
    return dest


def unzip(zip_path: Path, dest: Path, strip_root: bool = True) -> None:
    dest.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as z:
        for info in z.infolist():
            parts = info.filename.split("/")
            rel = "/".join(parts[1:]) if (strip_root and len(parts) > 1) else info.filename
            if not rel:
                continue
            target = dest / rel.replace("/", os.sep)
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(info) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out, 1 << 20)


def fix_cmdline_tools_layout() -> Path:
    """cmdline-tools.zip 解出来多一层 cmdline-tools/，摊平到 latest/。"""
    latest = SDK_DIR / "cmdline-tools" / "latest"
    inner = latest / "cmdline-tools"
    if inner.is_dir():
        for item in inner.iterdir():
            shutil.move(str(item), str(latest / item.name))
        inner.rmdir()
    return latest / "bin" / "sdkmanager.bat"


def main() -> int:
    BIN.mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.gettempdir()) / "pv-dl"
    tmp.mkdir(exist_ok=True)

    log("[1/5] JDK 17")
    if (JDK_DIR / "bin" / "java.exe").exists():
        log("  已安装，跳过")
    else:
        unzip(download(URLS["jdk17.zip"], tmp / "jdk17.zip"), JDK_DIR)

    log("[2/5] Gradle " + GRADLE_VER)
    if not (GRADLE_DIR / "bin" / "gradle.bat").exists():
        unzip(download(URLS["gradle.zip"], tmp / "gradle.zip"), GRADLE_DIR)

    log("[3/5] Android cmdline-tools")
    sdkmanager = SDK_DIR / "cmdline-tools" / "latest" / "bin" / "sdkmanager.bat"
    if not sdkmanager.exists():
        unzip(download(URLS["clt.zip"], tmp / "clt.zip"),
              SDK_DIR / "cmdline-tools" / "latest", strip_root=False)
        sdkmanager = fix_cmdline_tools_layout()

    log("[4/5] Android platform / build-tools")
    lic = SDK_DIR / "licenses"
    lic.mkdir(parents=True, exist_ok=True)
    for name, hashes in LICENSES.items():
        (lic / name).write_text("\n".join(hashes), encoding="ascii")

    env = {**os.environ, "JAVA_HOME": str(JDK_DIR),
           "PATH": f"{JDK_DIR / 'bin'}{os.pathsep}{os.environ.get('PATH', '')}",
           "ANDROID_HOME": str(SDK_DIR)}
    subprocess.run(
        [str(sdkmanager), "--sdk_root=" + str(SDK_DIR),
         "platform-tools", "platforms;android-34", "build-tools;34.0.0"],
        env=env, stdin=subprocess.DEVNULL, check=False,
    )

    log("[5/5] local.properties + gradle wrapper")
    (ANDROID_DIR / "local.properties").write_text(
        "sdk.dir=" + str(SDK_DIR).replace("\\", "\\\\"), encoding="ascii")
    subprocess.run(
        [str(GRADLE_DIR / "bin" / "gradle.bat"), "wrapper",
         "--gradle-version", GRADLE_VER, "--distribution-type", "bin", "-q"],
        cwd=str(ANDROID_DIR), env=env, check=False,
    )

    ok = (SDK_DIR / "platforms" / "android-34" / "android.jar").exists()
    log("")
    log(f"Android SDK: {SDK_DIR}  (platform 34 {'OK' if ok else '缺失'})")
    log("构建：")
    log(f"  cd {ANDROID_DIR}")
    log("  gradlew.bat assembleDebug")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
