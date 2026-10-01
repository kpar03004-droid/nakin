# 낙인 카테고리 아이콘 — 16x16 픽셀 원본. PNG 와 캔버스용 SVG 를 같은 데이터에서 만든다.
import math, os, sys, json
from PIL import Image

PAL = {
    'K': '#3a2a1c', 'W': '#fff4d6',
    'g': '#e5c368', 'G': '#b8892a', 'Y': '#7a5a1a', 'y': '#f2d45c',
    's': '#d3d6db', 'S': '#9aa0a8', 'd': '#5f6570',
    'r': '#d0493c', 'R': '#8e2a22', 'o': '#e8913a', 'O': '#a85a20', 'a': '#f3cd86',
    'l': '#7cc44f', 'L': '#3f8a3a', 'b': '#8fcdf0', 'B': '#3f7fc0', 'n': '#274f86',
    'p': '#b58ae0', 'P': '#6a3fa0', 'w': '#c08a52', 'D': '#8a5a32',
    't': '#f1e3bf', 'T': '#d4bf8e', 'k': '#ee9aaa', 'e': '#e9e4da',
    'c': '#6fd8d0', 'C': '#2f9e9a',
}

def grid(rows):
    rows = list(rows)
    rows += ['.' * 16] * (16 - len(rows))
    for i, r in enumerate(rows):
        assert len(r) == 16, (i, r, len(r))
        for ch in r:
            assert ch == '.' or ch in PAL, (i, r, ch)
    return rows

E = '.' * 16
ICONS = {}

# 냥(기본) — 엽전
ICONS['coin'] = grid([E, E,
    '.....KKKKKK.....',
    '...KKggggggKK...',
    '..KgWgggggggGK..',
    '..KWggggggggGK..',
    '.KggggKKKKgggGK.',
    '.KggggK..KgggGK.',
    '.KggggK..KggGGK.',
    '.KggggKKKKggGGK.',
    '..KgggggggGGGK..',
    '..KggggggGGGGK..',
    '...KKGGGGGGKK...',
    '.....KKKKKK.....'])

# 대장장이 — 모루와 불똥
ICONS['blacksmith'] = grid([E,
    '...........y....',
    '.........y.W.y..',
    '...........y....',
    E,
    '...KKKKKKKKKKKK.',
    'KKKsWWWWWWWWWsK.',
    '.KKssssssssssSK.',
    '...KKSSSSSSSKK..',
    '.....KSSSSSK....',
    '.....KSSSSdK....',
    '....KSSSSSSdK...',
    '...KSSSSSSSSdK..',
    '...KddddddddddK.',
    '...KKKKKKKKKKKK.'])

# 농부 — 양파
ICONS['farmer'] = grid([
    '.........K......',
    '........KlK.....',
    '....K...KlK.....',
    '...KlK.KlK......',
    '....KlKKLK......',
    '.....KLLK.......',
    '......KooK......',
    '.....KaooDK.....',
    '....KaaoooDK....',
    '...KaaoooooDK...',
    '...KaoooooDDK...',
    '...KaoooooDDK...',
    '....KooooDDK....',
    '.....KKDDKK.....',
    '.....T.T.T......'])

# 요리사 — 김 나는 냄비
ICONS['chef'] = grid([
    '......e...e.....',
    '.....e...e......',
    '......e...e.....',
    '.....e...e......',
    E,
    '..KKKKKKKKKKKK..',
    '..KsssssssssSK..',
    'KKKsWWsssssSSKKK',
    'K.KsWssssssSSK.K',
    'KKKsssssssSSSKKK',
    '..KssssssSSSdK..',
    '..KSSSSSSSSddK..',
    '...KSSSSSSddK...',
    '....KKKKKKKK....'])

# 연금술사 — 둥근 플라스크
ICONS['alchemist'] = grid([
    '......KKKK......',
    '......KwwK......',
    '......KKKK......',
    '......KbbK......',
    '......KbWK......',
    '.....KbbbbK.....',
    '....KbbbbWbK....',
    '...KccccccccK...',
    '..KcWccccccCCK..',
    '..KWccccccccCK..',
    '..KcccccccCCCK..',
    '..KccccccCCCCK..',
    '...KcccCCCCCK...',
    '....KKKKKKKK....'])

# 다이버 — 잠수 헬멧
ICONS['diver'] = grid([E,
    '.....KKKKKK.....',
    '...KKggggggKK...',
    '..KgWggggggGGK..',
    '.KgWKKKKKKKKGGK.',
    '.KggKbbbbbbKGGK.',
    'KgggKbWbbbbKGGGK',
    'KgggKbbbbbbKGGGK',
    'KgggKbbbbbBKGGGK',
    '.KggKBbbbBBKGGK.',
    '.KggGKKKKKKGGGK.',
    '..KGGGGGGGGGGK..',
    '.KKYYYYYYYYYYKK.',
    'KYgYgYgYgYgYgYYK',
    'KKKKKKKKKKKKKKKK'])

# 낚시 — 물고기
ICONS['fishing'] = grid([E, E, E,
    '......KKKK......',
    '....KKnnnnKK....',
    '...KbbbbbbbbK.KK',
    '..KbWKbbbbbbbKBK',
    '.KbbbbbbbbbbbBBK',
    '.KbbbbbbbbbbBBBK',
    '..KWWbbbbbbbKBK.',
    '...KWWWWWbbK.KK.',
    '....KKKKKKK.....'])

# 무역상점 — 차양 친 노점
ICONS['trade_shop'] = grid([E,
    '.KKKKKKKKKKKKKK.',
    'KrrWWrrWWrrWWrrK',
    'KrrWWrrWWrrWWrrK',
    'KRRKWWKRRKWWKRRK',
    '.KK.KK.KK.KK.KK.',
    '..KwK......KwK..',
    '..KwK......KwK..',
    '..KwK.KKKK.KwK..',
    '..KwK.KggK.KwK..',
    'KKKKKKKKKKKKKKKK',
    'KwwwwwwwwwwwwwwK',
    'KDDDDDDDDDDDDDDK',
    'KKKKKKKKKKKKKKKK'])

# NPC 상점 — 종이 쇼핑백
ICONS['npc_shop'] = grid([E, E,
    '......KKKK......',
    '.....K....K.....',
    '.....K....K.....',
    '..KKKKKKKKKKKK..',
    '..KaaaaaaaaawK..',
    '..KaaaaaaaawwK..',
    '..KaaaaKKaaawK..',
    '..KaaaKrrKaawK..',
    '..KaaaKrRKaawK..',
    '..KaaaaKKaaawK..',
    '..KwwwwwwwwwwK..',
    '..KKKKKKKKKKKK..'])

# 거래소 — 시세판
ICONS['exchange'] = grid([E,
    'KKKKKKKKKKKKKKKK',
    'KttttttttttttttK',
    'KttttttttttlLttK',
    'KttttttttttlLttK',
    'KttttttttttlLttK',
    'KttttlLttttlLttK',
    'KttttlLtrRtlLttK',
    'KtrRtlLtrRtlLttK',
    'KtrRtlLtrRtlLttK',
    'KtrRtlLtrRtlLttK',
    'KtrRtlLtrRtlLttK',
    'KDDDDDDDDDDDDDDK',
    'KKKKKKKKKKKKKKKK',
    '..KK........KK..'])

# 플리마켓 — 가격표
ICONS['flea'] = grid([E, E, E,
    '.....KKKKKKKKKK.',
    '....KyyyyyyyyyK.',
    '...KyyyyyyyyyyK.',
    '..KyyKKyyyyyyyK.',
    '.KyyK.KyyYYYYyK.',
    '.KyyK.KyyyyyyyK.',
    '..KyyKKyyYYyyYK.',
    '...KyyyyyyyyYYK.',
    '....KyYYYYYYYYK.',
    '.....KKKKKKKKKK.'])

# 송금 — 엽전과 보내는 화살표
_coin9 = ['..KKKKK..', '.KgggggK.', 'KgWggggGK', 'KgWKKKgGK', 'KggK.KgGK',
          'KggKKKGGK', 'KgggggGGK', '.KgGGGGK.', '..KKKKK..']
_arrow = ['.......', '..K....', '..KoK..', 'KKKooK.', 'KoooooK', 'KKKooK.',
          '..KoK..', '..K....', '.......']
ICONS['transfer'] = grid([E, E, E] + [c + a for c, a in zip(_coin9, _arrow)])

# 직거래 — 주고받는 화살표
_right = ['..........KK....', '..........KllK..', 'KKKKKKKKKKKlllK.', 'KllllllllllllllK',
          'KLLLLLLLLLLLLLLK', 'KKKKKKKKKKKLLLK.', '..........KLLK..', '..........KK....']
def _mirror(rows, a, b):
    return [r[::-1].replace('l', a).replace('L', b) for r in rows]
ICONS['direct_trade'] = grid(_right + _mirror(_right, 'o', 'O'))

# 마을 금고 — 다이얼 금고
ICONS['village'] = grid([E,
    '.KKKKKKKKKKKKKK.',
    '.KSSSSSSSSSSSdK.',
    '.KSKKKKKKKKKSdK.',
    '.KSKsssssssKSdK.',
    '.KSKssKKKssKSdK.',
    '.KSKsKgggKsKSdK.',
    '.KSKsKgKgKsKSdK.',
    '.KSKsKgggKsKSdK.',
    '.KSKssKKKssKSdK.',
    '.KSKsssssssKSdK.',
    '.KSKKKKKKKKKSdK.',
    '.KSSSSSSSSSSSdK.',
    '.KddddddddddddK.',
    '.KKKKKKKKKKKKKK.',
    '..KK........KK..'])

# 인챈트 — 빛나는 마법서
ICONS['enchant'] = grid([
    '..............y.',
    '.............yWy',
    '...KKKKKKKKKK.y.',
    '..KPpppppppppK..',
    '..KPpppppppppK..',
    '..KPppppyppppK..',
    '..KPpppyWypppK..',
    '..KPppyWWWyppK..',
    '..KPpppyWypppK..',
    '..KPppppyppppK..',
    '..KPpppppppppK..',
    '..KPpppppppppK..',
    '..KPtttttttttK..',
    '..KKKKKKKKKKKK..'])

# 전직 — 훈장
ICONS['job_change'] = grid([
    '....KKK...KKK...',
    '....KrrK.KBBK...',
    '.....KrrKBBK....',
    '......KrKBK.....',
    '.......KKK......',
    '.....KKKKKKK....',
    '....KgGGGGGGK...',
    '...KgGGGWGGGGK..',
    '...KgGGWWWGGGK..',
    '...KgWWWWWWWGK..',
    '...KGGWWWWWGYK..',
    '...KGGWWGWWGYK..',
    '....KGGGGGYYK...',
    '.....KKKKKKK....'])

# 이용료 — 티켓
ICONS['service'] = grid([E, E, E, E,
    '.KKKKKKKKKKKKKK.',
    '.KoooooKoooooOK.',
    '.KoooooooWWWoOK.',
    '..KoWWoKooooOK..',
    '..KooooooWWWOK..',
    '.KoooooKoooooOK.',
    '.KOOOOOOOOOOOOK.',
    '.KKKKKKKKKKKKKK.'])

# 카드팩 — 겹친 카드
ICONS['card_pack'] = grid([E,
    '..KKKKKKKKK.....',
    '..KBbBbBbBK.....',
    '..KbBbBKKKKKKKKK',
    '..KBbBbKtttttttK',
    '..KbBbBKtttrtttK',
    '..KBbBbKttrrrttK',
    '..KbBbBKtrrrrrtK',
    '..KBbBbKttrrrttK',
    '..KbBbBKtttrtttK',
    '..KBbBbKtttttttK',
    '..KbBbBKtttttttK',
    '..KKKKKKtttttttK',
    '.......KtttttttK',
    '.......KKKKKKKKK'])

# 보상 — 선물 상자
ICONS['reward'] = grid([E,
    '.....KK..KK.....',
    '....KrrKKrrK....',
    '....KrKrrKrK....',
    '.KKKKKKrrKKKKKK.',
    '.KlllllrRlllllK.',
    '.KLLLLLrRLLLLLK.',
    '.KKKKKKKKKKKKKK.',
    '..KllllrRllllK..',
    '..KllllrRllllK..',
    '..KllllrRllllK..',
    '..KllllrRlllLK..',
    '..KLLLLrRLLLLK..',
    '..KKKKKKKKKKKK..'])

def raster(fn, extra=None):
    """(x,y) 픽셀 중심 → 색 글자. 채워진 칸 둘레를 K 로 외곽선."""
    g = [['.'] * 16 for _ in range(16)]
    for y in range(16):
        for x in range(16):
            c = fn(x + 0.5, y + 0.5)
            if c: g[y][x] = c
    out = [row[:] for row in g]
    for y in range(16):
        for x in range(16):
            if g[y][x] != '.': continue
            for dx, dy in ((1,0),(-1,0),(0,1),(0,-1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < 16 and 0 <= ny < 16 and g[ny][nx] not in '.K':
                    out[y][x] = 'K'; break
    return grid(''.join(r) for r in out)

# 수동 — 연필 (끝 좌하단 → 지우개 우상단)
def _pencil(px, py):
    ax, ay, bx, by = 2.2, 13.8, 13.6, 2.4
    L = math.hypot(bx - ax, by - ay)
    ux, uy = (bx - ax) / L, (by - ay) / L
    t = ((px - ax) * ux + (py - ay) * uy) / L
    d = (px - ax) * -uy + (py - ay) * ux      # + 는 좌상단 쪽
    half = 1.75
    if t < 0 or t > 1: return None
    if t < 0.30:                               # 깎인 끝
        w = half * (t / 0.30)
        if abs(d) > w + 0.35: return None
        return 'K' if t < 0.11 else 'w'
    if abs(d) > half: return None
    if t > 0.84: return 'k'                    # 지우개
    if t > 0.74: return 's'                    # 금속 띠
    return 'W' if d > 0.7 else ('y' if d > -0.7 else 'o')
ICONS['manual'] = raster(_pencil)

# 강화 — 곡괭이 + 강화 표시
def _pick(px, py):
    # 손잡이: (2,14) → (11,5)
    ax, ay, bx, by = 1.8, 14.2, 10.6, 5.4
    L = math.hypot(bx - ax, by - ay); ux, uy = (bx - ax) / L, (by - ay) / L
    t = ((px - ax) * ux + (py - ay) * uy) / L
    d = (px - ax) * -uy + (py - ay) * ux
    # 머리: 중심 (4,14) 반지름 11.3 원호의 띠, 손잡이 끝 주변만
    cx, cy = 3.2, 14.8
    r = math.hypot(px - cx, py - cy)
    ang = math.degrees(math.atan2(cy - py, px - cx))
    if 10.2 < r < 12.6 and 8 < ang < 82:
        return 'W' if r > 11.9 else ('s' if r > 11.0 else 'S')
    if 0 <= t <= 1 and abs(d) < 0.95:
        return 'w' if d > 0 else 'D'
    return None
_p = raster(_pick)
_plus = {  # 우하단 초록 +
    (12, 9): 'K', (13, 9): 'K', (14, 9): 'K',
    (11, 10): 'K', (12, 10): 'K', (13, 10): 'l', (14, 10): 'K', (15, 10): 'K',
}
_plus_rows = [
    '...........KKK..',
    '...........KlK..',
    '.........KKKlKKK',
    '.........KlllllK',
    '.........KKKlKKK',
    '...........KlK..',
    '...........KKK..',
]
_p = [list(r) for r in _p]
for i, row in enumerate(_plus_rows):
    y = 9 + i
    for x, ch in enumerate(row):
        if ch != '.': _p[y][x] = ch
ICONS['enhance'] = grid(''.join(r) for r in _p)

ORDER = [
    ('coin', '냥 (기본)', 'icon_coin'),
    ('blacksmith', '대장장이', 'icon_blacksmith'),
    ('farmer', '농부', 'icon_farmer'),
    ('chef', '요리사', 'icon_chef'),
    ('alchemist', '연금술사', 'icon_alchemist'),
    ('diver', '다이버', 'icon_diver'),
    ('fishing', '낚시', 'icon_fishing'),
    ('trade_shop', '무역상점', 'icon_trade_shop'),
    ('npc_shop', 'NPC 상점', 'icon_npc_shop'),
    ('exchange', '거래소', 'icon_exchange'),
    ('flea', '플리마켓', 'icon_flea'),
    ('transfer', '송금', 'icon_transfer'),
    ('direct_trade', '직거래', 'icon_direct_trade'),
    ('village', '마을 금고', 'icon_village'),
    ('enchant', '인챈트', 'icon_enchant'),
    ('enhance', '강화', 'icon_enhance'),
    ('job_change', '전직', 'icon_job_change'),
    ('service', '이용료', 'icon_service'),
    ('card_pack', '카드팩', 'icon_card_pack'),
    ('reward', '보상', 'icon_reward'),
    ('manual', '수동', 'icon_manual'),
]

def to_png(rows, path, scale=1):
    im = Image.new('RGBA', (16 * scale, 16 * scale), (0, 0, 0, 0))
    for y, r in enumerate(rows):
        for x, ch in enumerate(r):
            if ch == '.': continue
            h = PAL[ch]
            c = (int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16), 255)
            for yy in range(scale):
                for xx in range(scale):
                    im.putpixel((x * scale + xx, y * scale + yy), c)
    im.save(path)

def svg_paths(rows):
    by = {}
    for y, r in enumerate(rows):
        x = 0
        while x < 16:
            ch = r[x]
            if ch == '.': x += 1; continue
            s = x
            while x < 16 and r[x] == ch: x += 1
            by.setdefault(ch, []).append(f'M{s} {y}h{x - s}v1h-{x - s}z')
    return ''.join(f'<path fill="{PAL[ch]}" d="{"".join(ds)}"></path>' for ch, ds in by.items())

if __name__ == '__main__':
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    sheet = Image.new('RGBA', (7 * 16 * 8 + 6 * 8, 3 * 16 * 8 + 2 * 8), (241, 227, 191, 255))
    for i, (key, name, file) in enumerate(ORDER):
        to_png(ICONS[key], os.path.join(out, file + '.png'))
        to_png(ICONS[key], os.path.join(out, '_x8_' + key + '.png'), 8)
        im = Image.open(os.path.join(out, '_x8_' + key + '.png'))
        sheet.paste(im, ((i % 7) * (128 + 8), (i // 7) * (128 + 8)), im)
    sheet.save(os.path.join(out, '_sheet.png'))
    json.dump({k: svg_paths(ICONS[k]) for k, _, _ in ORDER}, open(os.path.join(out, 'svg.json'), 'w', encoding='utf-8'), ensure_ascii=False)
    print('ok', len(ORDER))
