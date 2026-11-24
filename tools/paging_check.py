"""验收：照片多到一页放不下时，网页端能不能一直往下翻到最早的那张。

起因是网页端写死 limit=300 又不翻页，2367 张的库只显示最新 300 张
（表现为"最多只能看到某年某月某日"）。这个脚本按同样规模灌数据，
用真实浏览器一路滚到底，确认条数、日期分组、搜索都对得上。

不碰任何真实数据：临时 DATA_DIR + 临时浏览器 profile。

用法：python tools/paging_check.py
"""

from __future__ import annotations

import base64
import json
import os
import shutil
import socket
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
PY = sys.executable
N = 2367            # 用户库里的实际条数
BOOT = "photovault"
NEWPW = "verify-pw-1234"


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


_id = 0
ws = None


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_port(port: int, timeout: float = 40.0) -> bool:
    end = time.time() + timeout
    while time.time() < end:
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/api/health", timeout=1)
            return True
        except Exception:
            time.sleep(0.3)
    return False


def seed(data_dir: Path) -> None:
    env = dict(os.environ, DATA_DIR=str(data_dir), PYTHONIOENCODING="utf-8")
    subprocess.run([PY, "-c", "import sys; sys.path.insert(0,'.'); from app import db; db.init_db()"],
                   cwd=str(SERVER), env=env, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    conn = sqlite3.connect(data_dir / "index.db")
    now = int(time.time() * 1000)          # 最新一张 = 现在，和用户一样
    step = 2 * 3600 * 1000                 # 平均每 2 小时一张
    rows = []
    for i in range(N):
        h = uuid.uuid4().hex + uuid.uuid4().hex
        rows.append((uuid.uuid4().hex, h, f"IMG_{i:04d}.jpg", f"{h[:2]}/{h[2:4]}/{h}.jpg",
                     "image", 200000, now - i * step, now - i * step, "admin-owner"))
    conn.executemany(
        "INSERT INTO photos(id,sha256,filename,rel_path,kind,size,taken_at,uploaded_at,owner_id)"
        " VALUES(?,?,?,?,?,?,?,?,?)", rows)
    conn.commit()
    conn.close()
    # 第 300 张（旧代码能显示的最后一屏末尾）落在哪天
    t300 = now - 299 * step
    print(f"  已写入 {N} 条；第 300 张 = {time.strftime('%Y-%m-%d', time.localtime(t300 / 1000))}"
          f"（旧代码就卡在这里）")
    print(f"  最早一张 = {time.strftime('%Y-%m-%d', time.localtime((now - (N - 1) * step) / 1000))}")


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
        return "<JS错误> " + str(res["exceptionDetails"].get("exception", {}).get("description"))[:200]
    return res.get("result", {}).get("value")


def pump(seconds: float, errors: list[str]):
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
            if e.get("level") == "error":
                errors.append("[log] " + str(e.get("text", ""))[:200])
    ws.settimeout(40)


def scroll_bottom(n: int = 120) -> int:
    """滚到底若干次，直到页面高度不再增长（自动加载停下来了）。"""
    last = -1
    for _ in range(n):
        ev("window.scrollTo(0, document.body.scrollHeight)")
        time.sleep(0.25)
        h = ev("document.body.scrollHeight")
        if h == last:
            break
        last = h
    return last


def shot(name: str) -> None:
    r = cmd("Page.captureScreenshot", format="png", captureBeyondViewport=False)
    data = r.get("result", {}).get("data")
    if data:
        p = ROOT / name
        p.write_bytes(base64.b64decode(data))
        print(f"  [截图] {p.name}")


def main() -> int:
    data_dir = Path(tempfile.mkdtemp(prefix="pv-pg-"))
    profile = Path(tempfile.mkdtemp(prefix="pv-edge-"))
    port = free_port()
    env = dict(os.environ, DATA_DIR=str(data_dir), ACCESS_TOKEN=BOOT, PYTHONIOENCODING="utf-8")

    print("准备数据：")
    seed(data_dir)

    srv = subprocess.Popen(
        [PY, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(port),
         "--log-level", "error"],
        cwd=str(SERVER), env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    browser_proc = None
    try:
        if not wait_port(port):
            print("测试服务没起来")
            return 1
        base = f"http://127.0.0.1:{port}"
        boot = httpx.Client(base_url=base, timeout=60)
        tok = boot.post("/api/auth/login", json={"username": "admin", "password": BOOT}).json()["token"]
        boot.post("/api/auth/password", headers={"Authorization": f"Bearer {tok}"},
                  json={"old_password": BOOT, "new_password": NEWPW})
        tok = boot.post("/api/auth/login", json={"username": "admin", "password": NEWPW}).json()["token"]
        api = httpx.Client(base_url=base, headers={"Authorization": f"Bearer {tok}"}, timeout=60)

        print("\n接口层：")
        p1 = api.get("/api/photos?limit=300&offset=0").json()
        p9 = api.get("/api/photos?limit=300&offset=2400").json()
        print(f"  total={p1['total']} 第1页={len(p1['items'])} 条，offset=2400 那页={len(p9['items'])} 条")
        ids = set()
        for off in range(0, N, 300):
            for it in api.get(f"/api/photos?limit=300&offset={off}").json()["items"]:
                ids.add(it["id"])
        print(f"  翻完 8 页去重后 = {len(ids)} 条（应 {N}，等于 total 才说明不重不漏）")
        hit = api.get("/api/photos?limit=10&q=IMG_2350").json()
        print(f"  服务端搜索 q=IMG_2350 -> total={hit['total']}，"
              f"首条={hit['items'][0]['filename'] if hit['items'] else '无'}（旧代码只能搜已加载的 300 条）")

        dbg = free_port()
        browser = find_browser()
        if not browser:
            print("\n没找到 Edge 或 Chrome，接口层已验完，跳过浏览器部分")
            return 0
        print(f"\n浏览器：{Path(browser).name}")
        browser_proc = subprocess.Popen(
            [browser, "--headless=new", "--disable-gpu", "--no-first-run",
             "--no-default-browser-check", f"--remote-debugging-port={dbg}",
             "--remote-allow-origins=*", f"--user-data-dir={profile}", "about:blank"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        ws_url = None
        for _ in range(80):
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

        cmd("Page.navigate", url=base)
        pump(2.5, errors)
        ev(f"localStorage.setItem('pv_token', {json.dumps(tok)})")
        cmd("Page.reload")
        pump(4.0, errors)

        print("\n首屏（不滚动）：")
        first = ev("document.querySelectorAll('.cell').length")
        print("  格子数        :", first, "（应 = 300，即一页）")
        print("  状态栏        :", ev("document.getElementById('st').textContent"))
        print("  按钮文案      :", ev("document.getElementById('moreBtn').textContent"))
        print("  最早日期条    :", ev(
            "(() => { const b=[...document.querySelectorAll('.daybar')];"
            " return b.length ? b[b.length-1].textContent : '无'; })()"))
        shot("_shot_paging_first.png")

        print("\n一直往下滚，直到自然停止：")
        h = scroll_bottom()
        cells = ev("document.querySelectorAll('.cell').length")
        print("  格子数        :", cells, f"（应 {N}）")
        print("  日期分组数    :", ev("document.querySelectorAll('.daybar').length"))
        print("  状态栏        :", ev("document.getElementById('st').textContent"))
        print("  按钮文案      :", ev("document.getElementById('moreBtn').textContent"))
        print("  最早日期条    :", ev(
            "(() => { const b=[...document.querySelectorAll('.daybar')];"
            " return b.length ? b[b.length-1].textContent : '无'; })()"))
        print("  页面高度      :", h)
        shot("_shot_paging_bottom.png")

        # 同一天的张数有没有因为跨页被拆成两段
        dup_days = ev(
            "(() => { const keys=[...document.querySelectorAll('.daybar')].map(e=>e.dataset.day);"
            " const seen=new Set(), dup=[]; keys.forEach(k=>{ if(seen.has(k)) dup.push(k); seen.add(k);});"
            " return dup.length; })()")
        print("  重复的日期条  :", dup_days, "（应 0：同一天不能被跨页拆开）")

        print("\n搜索（服务端，早期照片也应能搜到）：")
        ev("(() => { const q=document.getElementById('q'); q.value='IMG_2350';"
           " q.dispatchEvent(new Event('input')); })()")
        pump(2.0, errors)
        print("  格子数        :", ev("document.querySelectorAll('.cell').length"), "（应 1）")
        print("  状态栏        :", ev("document.getElementById('st').textContent"))
        searched = ev("document.querySelectorAll('.cell').length")
        ev("(() => { const q=document.getElementById('q'); q.value='';"
           " q.dispatchEvent(new Event('input')); })()")
        pump(2.0, errors)

        print("\n清空搜索后回到第一页：")
        first_again = ev("document.querySelectorAll('.cell').length")
        print("  格子数        :", first_again, "（应 300）")

        # 缩略图 404 是合成数据没落盘真实文件，属预期；别的报错才算问题
        thumb404 = [e for e in errors if "404" in e]
        real_err = [e for e in errors if "404" not in e]
        if thumb404:
            print(f"\n（缩略图 404 ×{len(thumb404)}：合成数据没有真实文件，属预期）")
        if real_err:
            print("\n--- 页面 JS 报错 ---")
            for e in real_err[:8]:
                print("  " + e.replace("\n", " ")[:240])
        ws.close()

        problems = []
        if first != 300:
            problems.append(f"首屏格子数 {first} != 300")
        if cells != N:
            problems.append(f"滚到底格子数 {cells} != {N}")
        if dup_days:
            problems.append(f"重复日期条 {dup_days} 个")
        if searched != 1:
            problems.append(f"搜索命中 {searched} != 1")
        if first_again != 300:
            problems.append(f"清空搜索后 {first_again} != 300")
        if len(ids) != N:
            problems.append(f"接口翻页去重后 {len(ids)} != {N}")
        if real_err:
            problems.append(f"页面 JS 报错 {len(real_err)} 条")
        if problems:
            print("\n[!!] 不通过：" + "；".join(problems))
            return 1
        print("\n[OK] 全部通过")
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
