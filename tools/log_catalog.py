"""동글랜드 채팅 로그 → 거래 원문 카탈로그.

사용법:  python log_catalog.py [로그폴더] [출력.md]
기본 로그폴더는 Feather 프로필(%APPDATA%/.dawn/profiles/26-1-2/.minecraft/logs).
*.log.gz 만 읽는다 (latest.log 는 게임 실행 중 잠겨 있음). 원본은 읽기만 한다.

출력물에는 다른 유저 닉네임과 채팅 원문이 들어간다 → 공개 저장소에 올리지 말 것.
"""
import collections
import datetime as dt
import glob
import gzip
import os
import re
import sys

DEFAULT_LOGS = os.path.expandvars(r"%APPDATA%\.dawn\profiles\26-1-2\.minecraft\logs")
LINE = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\] \[[^\]]+\] \[[^\]]+\]: (?:\[[^\]]*\] )?\[CHAT\] (.*)$")
FILE_DATE = re.compile(r"(\d{4})-(\d\d)-(\d\d)-\d+\.log\.gz$")

# 유저 대화 채널 (거래 메시지가 아님)
PLAYER_CHAT = re.compile(
    r"\[전챗\]|^\[지역채팅\]|^\[눈꽃\] <|^(?:마을원|마을장|부마을장) \S+ : |님의 질문|질문을 등록|답변이 등록"
)
MONEY = re.compile(r"냥")
# 금액 없이 ΔG 로만 값이 드러날 수 있는 거래 후보
NO_AMOUNT_KW = re.compile(
    r"강화|인챈트|모루|조율|전직|초기화|구매|판매|수령|지불|비용|소모|결제|차감|감정|뒤집|업그레이드|수리|제작 완료|교환"
)
NICK = re.compile(r"(?<![A-Za-z0-9_])[A-Za-z0-9_]{3,16}(?![A-Za-z0-9_])")
KEEP_ASCII = {"ModCheck", "VVIP", "NORMAL", "RARE", "EPIC"}


def template(msg: str) -> str:
    t = NICK.sub(lambda m: m.group(0) if m.group(0) in KEEP_ASCII or m.group(0).isdigit() else "<닉>", msg)
    t = re.sub(r"\d[\d,]*(?:\.\d+)?", "N", t)
    return re.sub(r"\s+", " ", t).strip()


def read_logs(folder):
    for path in sorted(glob.glob(os.path.join(folder, "*.log.gz"))):
        m = FILE_DATE.search(path)
        day = dt.date(int(m.group(1)), int(m.group(2)), int(m.group(3))) if m else None
        with gzip.open(path, "rt", encoding="utf-8", errors="replace") as fh:
            for raw in fh:
                lm = LINE.match(raw.rstrip("\n"))
                if not lm:
                    continue
                hh, mm, ss, msg = lm.groups()
                sec = int(hh) * 3600 + int(mm) * 60 + int(ss)
                yield os.path.basename(path), day, sec, msg


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_LOGS
    out = sys.argv[2] if len(sys.argv) > 2 else "원문_카탈로그(로컬전용).md"

    rows = [r for r in read_logs(folder) if not PLAYER_CHAT.search(r[3])]
    money = collections.OrderedDict()
    nomoney = collections.OrderedDict()
    for i, (fname, day, sec, msg) in enumerate(rows):
        t = template(msg)
        if not t:
            continue
        bucket = money if MONEY.search(msg) else (nomoney if NO_AMOUNT_KW.search(msg) else None)
        if bucket is None:
            continue
        e = bucket.setdefault(t, {"n": 0, "ex": [], "idx": i})
        e["n"] += 1
        if len(e["ex"]) < 2:
            e["ex"].append(f"{day} {sec // 3600:02d}:{sec % 3600 // 60:02d}:{sec % 60:02d}  {msg}")

    def context(idx):
        fname, _, sec, _ = rows[idx]
        lines = []
        for j in range(max(0, idx - 8), min(len(rows), idx + 9)):
            f2, _, s2, m2 = rows[j]
            if f2 == fname and abs(s2 - sec) <= 5:
                mark = ">>" if j == idx else "  "
                lines.append(f"{mark} +{s2 - sec:>2}s  {m2}")
        return lines

    with open(out, "w", encoding="utf-8") as w:
        w.write("# 동글랜드 거래 원문 카탈로그 (로컬 전용 — 타 유저 닉네임 포함, 공개 저장소 금지)\n\n")
        w.write(f"- 로그 폴더: `{folder}`\n- 시스템 메시지 {len(rows):,}줄 · 냥 유형 {len(money):,} · 금액없는 거래후보 유형 {len(nomoney):,}\n")
        w.write("- 템플릿 규칙: 숫자(소수·쉼표 포함) → `N`, 영문 닉 → `<닉>`\n\n")
        w.write("## A. 냥이 들어간 시스템 메시지 (빈도순)\n\n")
        for t, e in sorted(money.items(), key=lambda kv: -kv[1]["n"]):
            w.write(f"### [{e['n']}] {t}\n")
            for ex in e["ex"]:
                w.write(f"    {ex}\n")
            if e["n"] >= 2 or len(money) < 400:
                w.write("  문맥(±5초):\n")
                for c in context(e["idx"]):
                    w.write(f"    {c}\n")
            w.write("\n")
        w.write("## B. 냥 없는 거래 후보 (강화·모루·구매 등 — ΔG 로만 금액이 보일 수 있음)\n\n")
        for t, e in sorted(nomoney.items(), key=lambda kv: -kv[1]["n"]):
            w.write(f"- [{e['n']}] {t}\n")
            for ex in e["ex"][:1]:
                w.write(f"    {ex}\n")
    print(f"rows={len(rows)} money={len(money)} nomoney={len(nomoney)} -> {out}")


if __name__ == "__main__":
    main()
