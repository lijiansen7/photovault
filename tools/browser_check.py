"""用真实浏览器（Edge/Chrome + CDP）跑一遍网页端操作，定位前端问题。

前端 bug 光看代码很难确定（比如"点了没反应"到底是没选中、请求失败、
还是页面卡住），这里直接驱动真实浏览器按按钮，把每一步的中间状态打出来。

不碰真实数据：自己起隔离的测试服务 + 临时数据目录，
并按前端一次加载量（limit=300）塞同样的条数，规模才对得上。

用法：python tools/browser_check.py
"""

from __future__ import annotations

import base64
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid
from pathlib import Path

import httpx
import websocket

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "server"
sys.path.insert(0, str(Path(__file__).parent))

from e2e_live import make_photo, wait_port, free_port  # noqa: E402

BOOT = "test-boot-token"      # 初始管理员密码（= 服务端的 ACCESS_TOKEN）
NEWPW = "browser-check-pw-9"  # 测试里改成这个，绕开「必须改密」
ROWS = 300          # 前端一次加载 limit=300，测试规模与之一致

_id = 0
ws = None


def find_browser() -> str | None:
    pf = Path(os.environ.get("ProgramFiles", r"C:\Program Files"))
    pf86 = Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"))
    for p in (pf86 / "Microsoft/Edge/Application/msedge.exe",
              pf / "Microsoft/Edge/Application/msedge.exe",
              pf / "Google/Chrome/Application/chrome.exe",
              pf86 / "Google/Chrome/Application/chrome.exe",
              Path(os.environ.get("LOCALAPPDATA", "")) / "Google/Chrome/Application/chrome.exe"):
        if p.is_file():
            return str(p)
    return shutil.which("msedge") or shutil.which("chrome")


def cmd(method: str, **params):
    global _id
    _id += 1
    ws.send(json.dumps({"id": _id, "method": method, "params": params}))
    deadline = time.time() + 40
    while time.time() < deadline:
        m = json.loads(ws.recv())
        if m.get("id") == _id:
            return m
    raise TimeoutError(method)


def ev(expr: str, await_promise: bool = False):
    r = cmd("Runtime.evaluate", expression=expr, returnByValue=True, awaitPromise=await_promise)
    res = r.get("result", {})
    if "exceptionDetails" in res:
        d = res["exceptionDetails"]
        return "<JS错误> " + str(d.get("exception", {}).get("description", d))[:200]
    return res.get("result", {}).get("value")


def click_by_text(text: str) -> str:
    return ev(
        "(() => {"
        f"  const b = [...document.querySelectorAll('button')].find(x => x.textContent.trim().startsWith('{text}'));"
        "  if (!b) return 'NOT_FOUND';"
        "  if (b.classList.contains('hide')) return 'HIDDEN';"
        "  b.click(); return 'CLICKED';"
        "})()"
    )


def click_modal_ok() -> str:
    """按 id 精确点弹窗里的确定键 —— 用文案匹配会误中工具栏上同前缀的按钮。"""
    return ev(
        "(() => {"
        "  const m = document.getElementById('modal');"
        "  if (m.style.display !== 'flex') return 'MODAL_NOT_OPEN';"
        "  document.getElementById('modalOk').click(); return 'CLICKED';"
        "})()"
    )


def shot(name: str, note: str = "") -> None:
    """截屏存到项目根，方便人眼确认视觉效果。"""
    r = cmd("Page.captureScreenshot", format="png")
    data = r.get("result", {}).get("data")
    if not data:
        print(f"  [截图失败] {name}")
        return
    p = ROOT / name
    p.write_bytes(base64.b64decode(data))
    print(f"  [截图] {p.name} {note}")


def prepare_db(data_dir: Path, env: dict) -> str:
    """建库并塞入记录：生成 300 张真图太慢，直接写索引（缩略图会 404，但格子在）。"""
    subprocess.run(
        [sys.executable, "-c", "import sys; sys.path.insert(0,'.'); from app import db; db.init_db()"],
        cwd=str(SERVER), env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    conn = sqlite3.connect(data_dir / "index.db")
    now = int(time.time() * 1000)
    rows = []
    for i in range(ROWS):
        h = uuid.uuid4().hex + uuid.uuid4().hex
        # 时间放到很早，让后传的真图排在最前面 —— 截图才看得到真实缩略图
        taken = 1_000_000_000_000 - i * 3_600_000
        rows.append((uuid.uuid4().hex, h, f"IMG_{i}.jpg", f"{h[:2]}/{h[2:4]}/{h}.jpg",
                     300_000 + i * 1000, taken, "", 1, now - i * 1000))
    conn.executemany(
        "INSERT INTO photos(id,sha256,filename,rel_path,size,taken_at,owner_id,kind,uploaded_at)"
        " VALUES(?,?,?,?,?,?,?,?,?)", rows)
    conn.commit()
    conn.close()
    return "已写入 %d 条索引记录" % len(rows)


def main() -> int:
    data_dir = Path(tempfile.mkdtemp(prefix="pv-br-"))
    profile = Path(tempfile.mkdtemp(prefix="pv-edge-"))
    port = free_port(0)
    env = dict(os.environ)
    env["DATA_DIR"] = str(data_dir)
    env["ACCESS_TOKEN"] = BOOT
    env["PYTHONIOENCODING"] = "utf-8"

    print(prepare_db(data_dir, env))

    srv = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1",
         "--port", str(port), "--log-level", "error"],
        cwd=str(SERVER), env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    browser_proc = None
    try:
        if not wait_port(port):
            print("测试服务没起来")
            return 1
        base = f"http://127.0.0.1:{port}"
        boot = httpx.Client(base_url=base, timeout=60)

        # 没有旧 Token 通道了，先账号登录换 token
        r = boot.post("/api/auth/login", json={"username": "admin", "password": BOOT})
        assert r.status_code == 200, f"登录失败 {r.status_code}：{r.text[:160]}"
        tok = r.json()["token"]
        # 初始管理员被标记「必须改密」，不改的话前端会卡在改密页
        boot.post("/api/auth/password",
                  headers={"Authorization": f"Bearer {tok}"},
                  json={"old_password": BOOT, "new_password": NEWPW})
        tok = boot.post("/api/auth/login",
                        json={"username": "admin", "password": NEWPW}).json()["token"]

        api = httpx.Client(base_url=base, headers={"Authorization": f"Bearer {tok}"}, timeout=60)

        # 传几张真图，截图上才看得到缩略图和真实观感
        for i in range(6):
            ph = data_dir / f"REAL_{i}.jpg"
            try:
                make_photo(ph, tint=i * 38)
                api.post("/api/upload", data={"device_id": "br", "meta": "{}"},
                         files={"file": (ph.name, ph.read_bytes(), "image/jpeg")})
            except Exception:
                pass
        print(f"测试服务就绪：{api.get('/api/photos').json()['total']} 条\n")

        browser = find_browser()
        if not browser:
            print("没找到 Edge 或 Chrome，跳过浏览器检查")
            return 0
        print(f"用 {Path(browser).name} 驱动\n")

        dbg = free_port(0)
        browser_proc = subprocess.Popen(
            [browser, "--headless=new", "--disable-gpu", "--no-first-run",
             "--no-default-browser-check", f"--remote-debugging-port={dbg}",
             "--remote-allow-origins=*", f"--user-data-dir={profile}", "about:blank"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        ws_url = None
        for _ in range(60):
            try:
                tabs = json.load(urllib.request.urlopen(f"http://127.0.0.1:{dbg}/json/list", timeout=2))
                for t in tabs:
                    if t.get("type") == "page":
                        ws_url = t["webSocketDebuggerUrl"]
                        break
                if ws_url:
                    break
            except Exception:
                pass
            time.sleep(0.5)
        if not ws_url:
            print("浏览器没起来")
            return 1

        global ws
        ws = websocket.create_connection(ws_url, timeout=30)
        cmd("Page.enable")
        cmd("Runtime.enable")
        cmd("Log.enable")

        errors: list[str] = []

        def pump(seconds: float):
            ws.settimeout(0.3)
            end = time.time() + seconds
            while time.time() < end:
                try:
                    m = json.loads(ws.recv())
                except Exception:
                    continue
                if m.get("method") == "Runtime.exceptionThrown":
                    d = m["params"]["exceptionDetails"]
                    errors.append(str(d.get("exception", {}).get("description", d))[:300])
                elif m.get("method") == "Log.entryAdded":
                    e = m["params"]["entry"]
                    if e.get("level") == "error" and "favicon" not in str(e.get("text", "")).lower():
                        errors.append("[log] " + str(e.get("text", ""))[:200])
            ws.settimeout(40)

        cmd("Page.navigate", url=base)
        pump(3.0)
        ev(f"localStorage.setItem('pv_token', {json.dumps(tok)})")
        cmd("Page.reload")
        pump(4.0)

        print("--- 页面状态 ---")
        print("  版本标记      :", ev("document.getElementById('build').textContent"))
        print("  app 可见      :", ev("!document.getElementById('app').classList.contains('hide')"))
        print("  照片格子数    :", ev("document.querySelectorAll('.cell').length"))
        print("  日期分组数    :", ev("document.querySelectorAll('.daybar').length"))
        print("  复选框可见数  :", ev("document.querySelectorAll('.pick:not(.hide)').length"))
        shot("_shot_web_light.png", "（照片墙 · 浅色）")

        # 顺手确认深色模式也正常
        cmd("Emulation.setEmulatedMedia",
            features=[{"name": "prefers-color-scheme", "value": "dark"}])
        pump(1.0)
        shot("_shot_web_dark.png", "（照片墙 · 深色）")
        cmd("Emulation.setEmulatedMedia", features=[])
        pump(0.5)

        print("\n--- 操作 ---")
        print("  点「批量选择」:", click_by_text("批量选择"))
        pump(0.5)
        print("  复选框可见数  :", ev("document.querySelectorAll('.pick:not(.hide)').length"))

        print("  空选点删除    :", click_by_text("删除所选"))
        pump(1.0)
        print("    toast 提示  :", ev("document.getElementById('toast').style.display === 'block'"
                                    " ? document.getElementById('toast').textContent.split('。')[0]"
                                    " : '没有提示 <-- 没反应'"))

        print("  点「全选」    :", click_by_text("全选"))
        pump(0.8)
        print("    已勾选      :", ev("document.querySelectorAll('.pick:checked').length"))
        print("    按钮文案    :", ev("document.getElementById('delBtn').textContent"))

        print("\n--- 执行删除 ---")
        t0 = time.time()
        print("  点「删除所选」:", click_by_text("删除所选"))
        pump(1.2)
        print("    自己的弹窗  :", ev("document.getElementById('modal').style.display"),
              "|", str(ev("document.getElementById('modalMsg').innerText") or "").replace("\n", " ")[:52])
        print("    点「删除」  :", click_modal_ok())
        pump(4.0)
        dt = time.time() - t0
        print("  状态栏        :", ev("document.getElementById('st').textContent"))
        left = api.get("/api/photos").json()["total"]
        trash = len(api.get("/api/trash?limit=1000").json()["items"])
        print(f"  服务端：剩余 {left}，回收站 {trash}（耗时约 {dt:.1f}s）")
        if left != 0:
            print("  >>> 删除没生效！")

        print("\n--- 回收站视图 ---")
        print("  点「回收站」  :", click_by_text("回收站"))
        pump(2.0)
        print("  批量选择可见  :", ev("!document.getElementById('selBtn').classList.contains('hide')"), "（应 False）")
        print("  回收站格子数  :", ev("document.querySelectorAll('.cell').length"))

        if errors:
            print("\n--- 页面 JS 报错 ---")
            for e in errors[:8]:
                print("  " + e.replace("\n", " ")[:240])
        else:
            print("\n页面无 JS 报错")

        ws.close()
        return 0
    finally:
        if browser_proc and browser_proc.poll() is None:
            browser_proc.terminate()
        if srv.poll() is None:
            srv.terminate()
            try:
                srv.wait(timeout=10)
            except subprocess.TimeoutExpired:
                srv.kill()
        shutil.rmtree(data_dir, ignore_errors=True)
        shutil.rmtree(profile, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
