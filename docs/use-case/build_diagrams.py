"""Rebuild the UML use-case drawings, editable PlantUML sources and PDF.

Uses only reportlab and local Arial fonts. Coordinates are shared by SVG/PDF.
Run from any directory with the Codex workspace Python or another Python that
has reportlab installed. The application itself is not changed.
"""

from pathlib import Path
from math import atan2, cos, sin, hypot
from html import escape
import json

from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A3, landscape
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
PDF = ROOT / "output" / "pdf" / "giavien-use-case.pdf"
FONT_DIR = Path("C:/Windows/Fonts")
pdfmetrics.registerFont(TTFont("Arial", str(FONT_DIR / "arial.ttf")))
pdfmetrics.registerFont(TTFont("Arial-Bold", str(FONT_DIR / "arialbd.ttf")))

INK = "#21323a"
MUTED = "#52646c"
LINE = "#6b7c84"
BORDER = "#a6b3b8"
TEAL = "#087b78"
ORANGE = "#a65d18"
FILLS = {"public": "#f2f5f7", "customer": "#eaf6f3", "staff": "#edf3fa",
         "admin": "#f5effa", "payment": "#fff4e8", "helper": "#f7f9fa"}


class Drawing:
    def __init__(self, w, h):
        self.w, self.h, self.items = w, h, []

    def rect(self, x, y, w, h, fill="#ffffff", stroke=BORDER, width=1.5):
        self.items.append(dict(kind="rect", x=x, y=y, w=w, h=h,
                               fill=fill, stroke=stroke, width=width))

    def ellipse(self, x, y, rx, ry, fill, stroke=INK, width=1.6):
        self.items.append(dict(kind="ellipse", x=x, y=y, rx=rx, ry=ry,
                               fill=fill, stroke=stroke, width=width))

    def text(self, x, y, value, size=20, color=INK, bold=False, anchor="middle"):
        self.items.append(dict(kind="text", x=x, y=y, value=value, size=size,
                               color=color, bold=bold, anchor=anchor))

    def path(self, points, color=LINE, width=1.8, dashed=False, arrow=None):
        self.items.append(dict(kind="path", points=points, color=color,
                               width=width, dashed=dashed, arrow=arrow))

    def label(self, x, y, lines, color=TEAL, size=17):
        for i, value in enumerate(lines):
            tw = pdfmetrics.stringWidth(value, "Arial", size)
            self.rect(x-tw/2-6, y+i*22-size+1, tw+12, size+6,
                      fill="#ffffff", stroke=None)
            self.text(x, y+i*22, value, size=size, color=color)

    def note(self, x, y, w, heading, lines):
        h = 55 + len(lines)*29
        self.rect(x, y, w, h, fill="#fffdf6", stroke="#c7bfa1", width=1.2)
        self.text(x+20, y+31, heading, size=19, bold=True, anchor="start")
        for i, line in enumerate(lines):
            assert pdfmetrics.stringWidth(line, "Arial", 18) <= w-38, line
            self.text(x+20, y+65+i*29, line, size=18, anchor="start")

    def actor(self, x, y, lines, abstract=False):
        self.ellipse(x, y+15, 15, 15, "#ffffff", width=2)
        self.path([(x, y+30), (x, y+84)], color=INK, width=2)
        self.path([(x-32, y+50), (x+32, y+50)], color=INK, width=2)
        self.path([(x-28, y+118), (x, y+84), (x+28, y+118)], color=INK, width=2)
        for i, line in enumerate(lines):
            self.text(x, y+145+i*25, line, size=19, bold=i == 0)
        if abstract:
            self.text(x, y+145+len(lines)*25, "«abstract»", size=16, color=MUTED)


def node(id, title, x, y, group="customer", rx=225, ry=49):
    return dict(id=id, title=title.split("\n"), x=x, y=y,
                rx=rx, ry=max(ry, 26+len(title.split("\n"))*16), group=group)


def endpoint(n, toward):
    dx, dy = toward[0]-n["x"], toward[1]-n["y"]
    k = 1 / ((dx/n["rx"])**2 + (dy/n["ry"])**2)**0.5
    return (n["x"]+dx*k, n["y"]+dy*k)


def add_nodes(d, nodes):
    for n in nodes:
        d.ellipse(n["x"], n["y"], n["rx"], n["ry"], FILLS[n["group"]])
        count = len(n["title"])
        d.text(n["x"], n["y"]-(count-1)*13-17, n["id"], size=14,
               color=MUTED, bold=True)
        for i, line in enumerate(n["title"]):
            assert pdfmetrics.stringWidth(line, "Arial", 21) < n["rx"]*1.78, line
            d.text(n["x"], n["y"]-(count-1)*13+11+i*27,
                   line, size=21)


def assoc(d, anchor, target, via=None):
    points = [anchor]+(via or [])
    # Keep the actor fan in the left corridor instead of passing behind ovals.
    if via and points[-1][0] < target["x"]-target["rx"] and points[-1][1] != target["y"]:
        points.append((target["x"]-target["rx"]-25, target["y"]))
    points.append(endpoint(target, points[-1]))
    d.path(points, width=1.6)


def relation(d, a, b, kind, label_at, condition=None, via=None):
    middle = via or []
    points = [endpoint(a, middle[0] if middle else (b["x"], b["y"]))]
    points += middle
    points.append(endpoint(b, middle[-1] if middle else (a["x"], a["y"])))
    color = ORANGE if kind == "extend" else TEAL if kind == "include" else INK
    d.path(points, color=color, dashed=kind != "generalization",
           arrow="triangle" if kind == "generalization" else "open")
    labels = [] if kind == "generalization" else ["«"+kind+"»"]
    if condition:
        labels += condition
    if labels:
        d.label(*label_at, labels, color=color, size=16 if condition else 17)


def scaffold(title, subtitle, height=1350, num=1):
    d = Drawing(1650, height)
    d.rect(0, 0, d.w, d.h, stroke=None)
    d.text(65, 63, "GiaViên  /  USE-CASE", size=19, color=TEAL, bold=True, anchor="start")
    d.text(65, 109, title, size=33, bold=True, anchor="start")
    d.text(65, 143, subtitle, size=18, color=MUTED, anchor="start")
    d.rect(310, 180, 1130, height-300, stroke=INK, width=1.7)
    d.text(332, 211, "HỆ THỐNG ĐẶT BÀN VÀ ĐẶT MÓN TRƯỚC GIAVIÊN",
           size=16, bold=True, anchor="start")
    y = height-65
    for x, kind, text in [(65, "assoc", "Liên kết tác nhân"),
                          (460, "include", "Hành vi bắt buộc"),
                          (850, "extend", "Hành vi tùy chọn / có điều kiện"),
                          (1340, "generalization", "Kế thừa")]:
        color = TEAL if kind == "include" else ORANGE if kind == "extend" else INK
        d.path([(x, y), (x+64, y)], color=color,
               dashed=kind in ("include", "extend"),
               arrow="triangle" if kind == "generalization" else
                     "open" if kind in ("include", "extend") else None)
        d.text(x+80, y+6, text, size=16, anchor="start")
    d.text(65, height-21, "Đối chiếu mã nguồn ngày 01/10/2026 • Tác nhân ở ngoài biên hệ thống",
           size=14, color=MUTED, anchor="start")
    d.text(1585, height-21, f"{num} / 6", size=14, color=MUTED, anchor="end")
    return d


DIAGRAMS = []


def save_model(stem, title, d, nodes, actors, associations, relations, notes):
    DIAGRAMS.append(dict(stem=stem, title=title, drawing=d, nodes=nodes,
                         actors=actors, associations=associations,
                         relations=relations, notes=notes))


def build_overview():
    d = scaffold("Sơ đồ tổng quan", "Phạm vi: một quán ăn • Các nhóm use-case được phân rã ở trang 2–6", 1240, 1)
    ns = [node("UC01–02", "Xem giới thiệu / thực đơn", 565, 270, "public"),
          node("UC03", "Đăng ký khách hàng", 565, 400, "public"),
          node("UC04", "Đăng nhập", 565, 530, "public"),
          node("KH01–07", "Đặt bàn / đặt món trước", 565, 660),
          node("KH08–13", "Theo dõi, sửa, hủy lượt riêng\nGửi yêu cầu đổi bàn / giờ", 565, 800),
          node("UC05", "Đăng xuất", 565, 930, "public"),
          node("TT01 / DM01", "Thanh toán cọc\nVNPAY hoặc QR demo", 1160, 270, "payment"),
          node("TT03–06", "Tiếp nhận / xử lý IPN", 1160, 400, "payment"),
          node("NV01–04 / KH09", "Xem lịch và vận hành phục vụ", 1160, 550, "staff"),
          node("NV05–09", "Đổi lịch / quán hủy\nGhi nhận kết quả hoàn tiền", 1160, 710, "staff"),
          node("QL01–09", "Quản trị món, bàn, tổ hợp\nCấu hình và tạo nhân viên", 1160, 905, "admin")]
    n = {a["id"]: a for a in ns}
    for target in ["UC01–02", "UC03", "UC04"]:
        assoc(d, (162, 385), n[target], [(255, 385)])
    for target in ["KH01–07", "KH08–13", "UC05"]:
        assoc(d, (162, 740), n[target], [(255, 740)])
    assoc(d, (162, 740), n["TT01 / DM01"],
          [(270, 740), (270, 1010), (865, 1010), (865, 270)])
    assoc(d, (1518, 350), n["TT01 / DM01"])
    assoc(d, (1518, 350), n["TT03–06"])
    for target in ["NV01–04 / KH09", "NV05–09"]:
        assoc(d, (1518, 610), n[target])
    assoc(d, (1518, 915), n["QL01–09"])
    d.path([(1595, 860), (1620, 860), (1620, 610), (1562, 610)],
           color=INK, arrow="triangle")
    add_nodes(d, ns)
    d.actor(130, 335, ["Khách truy cập"])
    d.actor(130, 690, ["Người dùng đã", "đăng nhập"], abstract=True)
    d.actor(1550, 300, ["VNPAY Sandbox"])
    d.actor(1550, 560, ["Nhân viên"])
    d.actor(1550, 865, ["Quản lý"])
    d.text(332, 1055, "Khách hàng và Nhân viên chuyên biệt hóa Người dùng đã đăng nhập; Quản lý kế thừa Nhân viên.",
           size=18, anchor="start")
    d.text(332, 1087, "Chức năng cá nhân chỉ áp dụng với lượt đặt do chính tài khoản tạo. QR demo được tách ở trang 6.",
           size=18, anchor="start")
    actors = {"guest": "Khách truy cập", "auth": "Người dùng đã đăng nhập",
              "customer": "Khách hàng", "staff": "Nhân viên", "admin": "Quản lý",
              "vnpay": "VNPAY Sandbox"}
    ass = [("guest", "UC01–02"), ("guest", "UC03"), ("guest", "UC04"),
           ("auth", "KH01–07"), ("auth", "KH08–13"), ("auth", "UC05"),
           ("auth", "TT01 / DM01"), ("vnpay", "TT01 / DM01"), ("vnpay", "TT03–06"),
           ("staff", "NV01–04 / KH09"), ("staff", "NV05–09"), ("admin", "QL01–09")]
    rels = [("customer", "auth", "generalization"), ("staff", "auth", "generalization"),
            ("admin", "staff", "generalization")]
    save_model("01-overview", "Sơ đồ tổng quan", d, ns, actors, ass, rels,
               ["VNPAY liên kết TT01; QR demo là chức năng mô phỏng nội bộ DM01.",
                "Quyền thao tác lượt cá nhân áp dụng cho chủ lượt đặt, kể cả STAFF / ADMIN.",
                "Các tài khoản cũng được truy cập trang công khai."])


def build_customer():
    d = scaffold("Chi tiết đặt bàn và quản lý lượt cá nhân", "Đăng nhập là tiền điều kiện • Không cần thanh toán ngay để tạo lượt giữ bàn", 1440, 2)
    ns = [node("KH01", "Tra cứu bàn trống", 600, 265),
          node("KH02", "Đặt bàn", 600, 450),
          node("KH07", "Đặt món trước", 600, 625),
          node("KH08", "Xem lịch sử lượt đặt", 600, 760),
          node("KH09", "Xem chi tiết / trạng thái\nPhản hồi và nhật ký", 600, 885),
          node("KH10", "Sửa món / ghi chú", 600, 1010),
          node("KH11", "Gửi yêu cầu đổi bàn / giờ", 600, 1135),
          node("KH12", "Hủy lượt đặt", 600, 1260),
          node("KH04", "Kiểm tra thời gian\nvà số khách", 1180, 265, "helper"),
          node("KH03", "Chọn bàn / tổ hợp ghép", 1180, 400, "helper"),
          node("KH05", "Kiểm tra bàn / tổ hợp\nvà xung đột lịch", 1180, 535, "helper"),
          node("KH06", "Tính cọc và giữ bàn", 1180, 670, "helper"),
          node("KH13", "Xác định xử lý cọc\nkhi hủy", 1180, 1260, "helper")]
    n = {a["id"]: a for a in ns}
    for target in ["KH01", "KH02", "KH08", "KH09", "KH10", "KH11", "KH12"]:
        assoc(d, (165, 420), n[target], [(280, 420)])
    relation(d, n["KH01"], n["KH04"], "include", (890, 250))
    for target, label_at in [("KH04", (885, 334)), ("KH03", (887, 418)),
                             ("KH05", (885, 502)), ("KH06", (890, 603))]:
        relation(d, n["KH02"], n[target], "include", label_at)
    relation(d, n["KH07"], n["KH02"], "extend", (764, 545),
             ["[có chọn món]"])
    relation(d, n["KH12"], n["KH13"], "include", (890, 1242))
    # The hierarchy is outside the system, with routes around actor labels.
    d.path([(130, 670), (65, 670), (65, 415), (98, 415)], color=INK, arrow="triangle")
    d.path([(130, 880), (40, 880), (40, 445), (98, 445)], color=INK, arrow="triangle")
    d.path([(130, 1090), (65, 1090), (65, 925), (98, 925)], color=INK, arrow="triangle")
    add_nodes(d, ns)
    d.actor(130, 370, ["Người dùng đã", "đăng nhập"], abstract=True)
    d.actor(130, 670, ["Khách hàng", "CUSTOMER"])
    d.actor(130, 880, ["Nhân viên", "STAFF"])
    d.actor(130, 1090, ["Quản lý", "ADMIN"])
    d.note(950, 785, 455, "Quy tắc của lượt cá nhân", [
        "• 1–12 khách; 4 chỗ / bàn; tối đa 3 bàn.",
        "• Ghép bàn liền nhau, cùng tầng.",
        "• Sửa món: trước ít nhất 2 giờ.",
        "• Yêu cầu đổi: đã xác nhận, chưa đến giờ.",
        "• Tự hủy: PENDING / CONFIRMED,",
        "  trước giờ đến; hoàn nếu còn ≥ 3 giờ.",
        "• Món đặt trước là tùy chọn.",
        "• Hết hạn giữ bàn: tự giải phóng nội bộ."])
    ass = [("auth", k) for k in ["KH01", "KH02", "KH08", "KH09", "KH10", "KH11", "KH12"]]
    rels = [("KH01", "KH04", "include")]+[("KH02", k, "include") for k in ["KH03", "KH04", "KH05", "KH06"]]
    rels += [("KH07", "KH02", "extend", "có chọn món; trước khi gửi đặt"),
             ("KH12", "KH13", "include"), ("customer", "auth", "generalization"),
             ("staff", "auth", "generalization"), ("admin", "staff", "generalization")]
    save_model("02-customer", "Đặt bàn và quản lý lượt cá nhân", d, ns,
               {"auth": "Người dùng đã đăng nhập", "customer": "Khách hàng", "staff": "Nhân viên", "admin": "Quản lý"},
               ass, rels, ["Sửa / hủy / yêu cầu đổi chỉ dành cho chủ lượt đặt.",
                           "Thanh toán cọc là mục tiêu riêng, không include trong KH02.",
                           "Tra cứu kiểm tra thời gian; tạo lượt kiểm tra lại bàn trong transaction."])


def build_staff():
    d = scaffold("Chi tiết vận hành của nhân viên", "Quản lý kế thừa các chức năng này • Nhân viên được xem và xử lý mọi lượt đặt", 1600, 3)
    ns = [node("NV01", "Xem / tìm lịch toàn quán\nVà thống kê nhanh", 600, 290, "staff"),
          node("KH09", "Xem chi tiết / trạng thái\nPhản hồi và nhật ký", 600, 390, "staff"),
          node("NV02", "Ghi nhận khách đã đến", 600, 515, "staff"),
          node("NV03", "Hoàn tất phục vụ", 600, 640, "staff"),
          node("NV04", "Ghi nhận khách không đến", 600, 765, "staff"),
          node("NV05", "Đổi bàn / giờ / số khách", 600, 890, "staff"),
          node("NV06", "Từ chối yêu cầu đổi", 600, 1015, "staff"),
          node("NV07", "Quán hủy lượt đặt", 600, 1140, "staff"),
          node("NV08", "Ghi nhận đã hoàn cọc", 600, 1265, "staff"),
          node("NV09", "Ghi nhận hoàn khoản thu thêm", 600, 1390, "staff"),
          node("NV10", "Kiểm tra bàn sẵn sàng", 1180, 515, "helper"),
          node("KH04", "Kiểm tra thời gian\nvà số khách mới", 1180, 850, "helper"),
          node("KH05", "Kiểm tra bàn / tổ hợp\nvà xung đột lịch", 1180, 985, "helper"),
          node("KH13", "Xác định xử lý cọc\nkhi hủy", 1180, 1140, "helper")]
    for item in ns:
        if item["id"] != "NV01":
            item["y"] += 25
    n = {a["id"]: a for a in ns}
    for target in ["NV01", "KH09", "NV02", "NV03", "NV04", "NV05", "NV06", "NV07", "NV08", "NV09"]:
        assoc(d, (165, 620), n[target], [(280, 620)])
    relation(d, n["NV02"], n["NV10"], "include", (890, 525))
    relation(d, n["NV05"], n["KH04"], "include", (890, 871))
    relation(d, n["NV05"], n["KH05"], "include", (890, 969))
    relation(d, n["NV07"], n["KH13"], "include", (890, 1149))
    d.path([(130, 1120), (55, 1120), (55, 620), (98, 620)], color=INK, arrow="triangle")
    add_nodes(d, ns)
    d.actor(130, 570, ["Nhân viên", "STAFF"])
    d.actor(130, 1120, ["Quản lý", "ADMIN"])
    d.note(950, 245, 455, "Điều kiện vận hành", [
        "• Nhận khách: đã xác nhận; từ 15 phút",
        "  trước giờ hẹn đến trước giờ kết thúc.",
        "• Hoàn tất: khách đã nhận bàn.",
        "• Không đến: hết thời gian chờ cấu hình."])
    d.note(950, 615, 455, "Đổi bàn / giờ", [
        "• Đã xác nhận và chưa đến giờ cũ.",
        "• Giữ nguyên số bàn của lượt đặt.",
        "• Đổi lịch không bắt buộc có yêu cầu chờ.",
        "• Từ chối cần một yêu cầu PENDING."])
    d.note(950, 1235, 455, "Hoàn tiền", [
        "• Chuyển tiền bên ngoài hệ thống.",
        "• Nhập mã hoàn để ghi nhận kết quả.",
        "• Cọc chính và khoản thu thêm tách riêng."])
    ass = [("staff", k) for k in ["NV01", "KH09", "NV02", "NV03", "NV04", "NV05", "NV06", "NV07", "NV08", "NV09"]]
    rels = [("admin", "staff", "generalization"), ("NV02", "NV10", "include"),
            ("NV05", "KH04", "include"), ("NV05", "KH05", "include"), ("NV07", "KH13", "include")]
    save_model("03-staff", "Vận hành của nhân viên", d, ns,
               {"staff": "Nhân viên", "admin": "Quản lý"}, ass, rels,
               ["Chỉ ghi nhận đã hoàn sau khi nhân viên thực hiện chuyển tiền bên ngoài.",
                "Quán hủy PENDING / CONFIRMED; đã nhận cọc thì chờ hoàn toàn bộ.",
                "CONFIRMED được xác nhận từ thanh toán, không có nhân viên duyệt cọc thủ công."])


def build_admin():
    d = scaffold("Chi tiết quản trị của quản lý", "Chỉ ADMIN có quyền cấu hình • Kế thừa quyền nhân viên ở trang 3", 1440, 4)
    ns = [node("QL01", "Xem món, bàn, tổ hợp\nvà tài khoản hệ thống", 600, 275, "admin"),
          node("QL02", "Lưu món ăn", 600, 455, "admin"),
          node("QL03", "Thêm món mới", 1180, 365, "admin"),
          node("QL04", "Sửa món / trạng thái bán", 1180, 555, "admin"),
          node("QL05", "Bật / tạm ngừng bàn", 600, 665, "admin"),
          node("QL06", "Thêm tổ hợp ghép bàn", 600, 815, "admin"),
          node("QL07", "Xóa tổ hợp ghép bàn", 600, 965, "admin"),
          node("QL08", "Cấu hình cọc / giữ bàn\nDọn bàn / chờ khách muộn", 600, 1115, "admin"),
          node("QL09", "Tạo tài khoản nhân viên", 600, 1265, "admin")]
    n = {a["id"]: a for a in ns}
    for target in ["QL01", "QL02", "QL05", "QL06", "QL07", "QL08", "QL09"]:
        assoc(d, (165, 550), n[target], [(280, 550)])
    relation(d, n["QL03"], n["QL02"], "generalization", (0, 0))
    relation(d, n["QL04"], n["QL02"], "generalization", (0, 0))
    add_nodes(d, ns)
    d.actor(130, 500, ["Quản lý", "ADMIN"])
    d.note(950, 675, 455, "Bàn và tổ hợp", [
        "• Tạm ngừng bàn cần xử lý lượt hiệu lực.",
        "• Tổ hợp gồm 2–3 bàn có thật.",
        "• Khi thêm: liền nhau cùng tầng,",
        "  không ghép qua vườn / thông tầng.",
        "• Không thêm / xóa bàn hoặc kéo thả."])
    d.note(950, 950, 455, "Phạm vi cấu hình", [
        "• Chỉ 4 thông số cọc và thời gian trên.",
        "• Cọc của lượt đặt cũ được giữ nguyên.",
        "• Danh mục món cố định; không xóa món.",
        "• Tạo tài khoản STAFF mới; email duy nhất.",
        "• Chưa sửa / khóa / xóa tài khoản."])
    ass = [("admin", k) for k in ["QL01", "QL02", "QL05", "QL06", "QL07", "QL08", "QL09"]]
    rels = [("QL03", "QL02", "generalization"), ("QL04", "QL02", "generalization")]
    save_model("04-admin", "Quản trị của quản lý", d, ns, {"admin": "Quản lý"}, ass, rels,
               ["QL03 và QL04 là hai biến thể của QL02 (id=0 thêm, id khác 0 cập nhật).",
                "ADMIN kế thừa quyền STAFF; chỉ trang này có quyền quản trị.",
                "QL08 không cấu hình giờ mở cửa, ngưỡng hoàn 3 giờ hay sửa món 2 giờ."])


def build_vnpay():
    d = scaffold("Chi tiết thanh toán cọc qua VNPAY Sandbox", "IPN xác minh mới ghi nhận tiền • Trang return chỉ hiển thị và thăm dò trạng thái", 1360, 5)
    ns = [node("TT01", "Khởi tạo thanh toán cọc\nqua VNPAY Sandbox", 600, 310, "payment"),
          node("TT02", "Xem kết quả / trạng thái\nthanh toán", 600, 555, "payment"),
          node("TT03", "Tiếp nhận và xử lý IPN", 600, 810, "payment"),
          node("TT04", "Kiểm tra chữ ký, mã\nvà số tiền giao dịch", 1180, 750, "helper"),
          node("TT05", "Ghi nhận / đối chiếu\nkết quả thanh toán", 1180, 955, "helper"),
          node("TT06", "Ghi nhận khoản thu thêm\nchờ hoàn riêng", 1180, 1170, "payment")]
    n = {a["id"]: a for a in ns}
    assoc(d, (165, 360), n["TT01"], [(265, 360)])
    assoc(d, (165, 360), n["TT02"], [(265, 360)])
    assoc(d, (165, 910), n["TT02"], [(265, 910)])
    assoc(d, (1518, 610), n["TT01"], [(1460, 610), (1460, 310)])
    assoc(d, (1518, 610), n["TT03"], [(1460, 840), (905, 840)])
    relation(d, n["TT03"], n["TT04"], "include", (890, 753))
    relation(d, n["TT03"], n["TT05"], "include", (890, 920))
    relation(d, n["TT06"], n["TT05"], "extend", (1007, 1042),
             ["[thành công thêm một", "giao dịch khác cọc chính]"])
    add_nodes(d, ns)
    d.actor(130, 310, ["Chủ lượt đặt", "đã đăng nhập"])
    d.actor(130, 860, ["Nhân viên / Quản lý", "xem trạng thái"])
    d.actor(1550, 560, ["VNPAY Sandbox"])
    d.note(950, 395, 455, "Khởi tạo / thử lại", [
        "• Chủ lượt; PENDING / UNPAID; còn hạn.",
        "• VNPAY được cấu hình hợp lệ.",
        "• Dùng lại giao dịch đang chờ.",
        "• Thất bại: tạo lần mới trong hạn.",
        "• Callback lặp không ghi nhận hai lần."])
    d.note(360, 640, 470, "Quyền xem kết quả", [
        "Return công khai với tham số hợp lệ.",
        "API: chủ lượt hoặc STAFF / ADMIN."])
    d.note(360, 960, 470, "Kết quả nhận cọc", [
        "• Thành công trong hạn: CONFIRMED / PAID.",
        "• Tiền đến khi lượt đã hủy / hết hạn:",
        "  REFUND_PENDING; không lấy lại bàn.",
        "• Thất bại: chưa xác nhận cọc.",
        "• Hoàn tiền do nhân viên làm ngoài hệ thống.",
        "• Chưa xác minh giao dịch Sandbox thực tế."])
    save_model("05-vnpay", "Thanh toán cọc VNPAY Sandbox", d, ns,
               {"owner": "Chủ lượt đặt đã đăng nhập", "staff": "Nhân viên / Quản lý (xem trạng thái)", "vnpay": "VNPAY Sandbox"},
               [("owner", "TT01"), ("owner", "TT02"), ("staff", "TT02"), ("vnpay", "TT01"), ("vnpay", "TT03")],
               [("TT03", "TT04", "include"), ("TT03", "TT05", "include"),
                ("TT06", "TT05", "extend", "thành công thêm một giao dịch khác cọc chính")],
               ["TT01 và TT03 độc lập; callback là tương tác bất đồng bộ, không quan hệ include thể hiện thứ tự.",
                "TT02 trên browser return không thay đổi trạng thái tiền; API trạng thái còn cho phép STAFF / ADMIN xem.",
                "Nhân viên ghi nhận hoàn cọc ở NV08 / NV09; hệ thống không gọi API hoàn VNPAY."])


def build_demo():
    d = scaffold("Chi tiết QR mô phỏng phục vụ demo", "Chức năng nội bộ • Không chuyển tiền thật • Chỉ bật khi demo cho phép và VNPAY chưa sẵn sàng", 1280, 6)
    ns = [node("DM01", "Mở phiên / tạo QR demo", 600, 300, "payment"),
          node("DM02", "Mở / xem trạng thái\nphiên demo", 600, 545, "payment"),
          node("DM03", "Tự bấm mô phỏng\nthanh toán thành công", 600, 810, "payment"),
          node("DM04", "Kiểm tra token, 60 giây\nvà hiệu lực lượt đặt", 1180, 745, "helper"),
          node("DM05", "Xác nhận cọc mô phỏng\nCONFIRMED / PAID", 1180, 935, "helper")]
    n = {a["id"]: a for a in ns}
    assoc(d, (165, 310), n["DM01"])
    assoc(d, (165, 660), n["DM02"], [(275, 660)])
    assoc(d, (165, 660), n["DM03"], [(275, 660)])
    relation(d, n["DM03"], n["DM04"], "include", (890, 753))
    relation(d, n["DM03"], n["DM05"], "include", (890, 917))
    add_nodes(d, ns)
    d.actor(130, 260, ["Chủ lượt đặt", "đã đăng nhập"])
    d.actor(130, 610, ["Người giữ mã", "QR demo"])
    d.note(950, 280, 455, "Phạm vi mô phỏng", [
        "• Chủ lượt tạo phiên khi còn giữ bàn.",
        "• Quét QR mở URL có token ngẫu nhiên.",
        "• Người giữ token không cần đăng nhập.",
        "• Sau 60 giây từ lúc tạo phiên mới bấm.",
        "• Chờ / quét mã không tự xác nhận cọc.",
        "• Tải lại không khởi động lại đếm giờ.",
        "• Chỉ công khai mã đặt, số tiền, trạng thái.",
        "• Bật VNPAY thì luồng QR demo tắt."])
    d.note(360, 1030, 1045, "Hiệu lực và đồng bộ", [
        "Lượt đã hủy / hết hạn không được xác nhận lại; thao tác lặp không tạo thêm khoản cọc demo.",
        "Các thiết bị đang mở phiên tự cập nhật kết quả. Dữ liệu ghi nhà cung cấp DEMO, không có tiền thật."])
    save_model("06-demo", "QR thanh toán mô phỏng", d, ns,
               {"owner": "Chủ lượt đặt đã đăng nhập", "bearer": "Người giữ mã QR demo"},
               [("owner", "DM01"), ("bearer", "DM02"), ("bearer", "DM03")],
               [("DM03", "DM04", "include"), ("DM03", "DM05", "include")],
               ["QR và DemoPaymentService nằm trong biên hệ thống; không có actor cổng QR bên ngoài.",
                "Sau 60 giây vẫn phải chủ động bấm mô phỏng thành công; không tự trả tiền.",
                "Bearer token hợp lệ đủ quyền xem / xác nhận mô phỏng, không cần đăng nhập."])


def arrow_points(points, triangle=False):
    x, y = points[-1]
    prev = next(p for p in reversed(points[:-1]) if hypot(x-p[0], y-p[1]) > 0.01)
    ang = atan2(y-prev[1], x-prev[0])
    length, spread = (21, 0.48) if triangle else (14, 0.48)
    return [(x-length*cos(ang-spread), y-length*sin(ang-spread)), (x, y),
            (x-length*cos(ang+spread), y-length*sin(ang+spread))]


def svg_of(d, title):
    s = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{d.w}" height="{d.h}" viewBox="0 0 {d.w} {d.h}" role="img" aria-labelledby="title desc">',
         f'<title id="title">{escape(title)} - GiaViên</title>',
         '<desc id="desc">Sơ đồ UML use-case. Tác nhân ngoài biên; oval là use-case; mũi tên đứt nét include hoặc extend; tam giác rỗng là kế thừa.</desc>']
    for e in d.items:
        k = e["kind"]
        if k == "rect":
            s.append(f'<rect x="{e["x"]}" y="{e["y"]}" width="{e["w"]}" height="{e["h"]}" fill="{e["fill"]}" stroke="{e["stroke"] or "none"}" stroke-width="{e["width"]}"/>')
        elif k == "ellipse":
            s.append(f'<ellipse cx="{e["x"]}" cy="{e["y"]}" rx="{e["rx"]}" ry="{e["ry"]}" fill="{e["fill"]}" stroke="{e["stroke"]}" stroke-width="{e["width"]}"/>')
        elif k == "text":
            s.append(f'<text x="{e["x"]}" y="{e["y"]}" text-anchor="{e["anchor"]}" font-family="Arial, sans-serif" font-size="{e["size"]}" font-weight="{700 if e["bold"] else 400}" fill="{e["color"]}">{escape(e["value"])}</text>')
        elif k == "path":
            pts = " ".join(f'{x:.2f},{y:.2f}' for x, y in e["points"])
            dash = ' stroke-dasharray="8 6"' if e["dashed"] else ""
            s.append(f'<polyline points="{pts}" fill="none" stroke="{e["color"]}" stroke-width="{e["width"]}"{dash}/>')
            if e["arrow"]:
                ap = " ".join(f'{x:.2f},{y:.2f}' for x, y in arrow_points(e["points"], e["arrow"] == "triangle"))
                tag = "polygon" if e["arrow"] == "triangle" else "polyline"
                fill = "#ffffff" if tag == "polygon" else "none"
                s.append(f'<{tag} points="{ap}" fill="{fill}" stroke="{e["color"]}" stroke-width="{e["width"]}"/>')
    s.append("</svg>")
    return "\n".join(s)


def rgb(color):
    return tuple(int(color[i:i+2], 16)/255 for i in (1, 3, 5))


def draw_pdf(c, d):
    page_w, page_h = landscape(A3)
    scale = min((page_w-42)/d.w, (page_h-32)/d.h)
    c.saveState()
    c.translate((page_w-d.w*scale)/2, (page_h-d.h*scale)/2)
    c.scale(scale, scale)
    for e in d.items:
        k = e["kind"]
        if k in ("rect", "ellipse"):
            c.setFillColorRGB(*rgb(e["fill"]))
            if e["stroke"]:
                c.setStrokeColorRGB(*rgb(e["stroke"]))
            c.setLineWidth(e["width"])
            c.setDash()
            if k == "rect":
                c.rect(e["x"], d.h-e["y"]-e["h"], e["w"], e["h"],
                       fill=1, stroke=bool(e["stroke"]))
            else:
                c.ellipse(e["x"]-e["rx"], d.h-e["y"]-e["ry"],
                          e["x"]+e["rx"], d.h-e["y"]+e["ry"], fill=1, stroke=1)
        elif k == "text":
            c.setFillColorRGB(*rgb(e["color"]))
            c.setFont("Arial-Bold" if e["bold"] else "Arial", e["size"])
            f = {"middle": c.drawCentredString, "start": c.drawString, "end": c.drawRightString}[e["anchor"]]
            f(e["x"], d.h-e["y"], e["value"])
        elif k == "path":
            c.setStrokeColorRGB(*rgb(e["color"]))
            c.setLineWidth(e["width"])
            c.setDash([8, 6] if e["dashed"] else [])
            p = c.beginPath()
            for i, (x, y) in enumerate(e["points"]):
                (p.moveTo if i == 0 else p.lineTo)(x, d.h-y)
            c.drawPath(p)
            if e["arrow"]:
                c.setDash()
                p = c.beginPath()
                for i, (x, y) in enumerate(arrow_points(e["points"], e["arrow"] == "triangle")):
                    (p.moveTo if i == 0 else p.lineTo)(x, d.h-y)
                tri = e["arrow"] == "triangle"
                if tri:
                    p.close()
                    c.setFillColorRGB(1, 1, 1)
                c.drawPath(p, stroke=1, fill=tri)
    c.restoreState()


def plantuml(model):
    lines = ["@startuml", "' UTF-8. UML content matches the accompanying vector drawings.",
             "left to right direction", "skinparam shadowing false", "skinparam defaultFontName Arial",
             "skinparam packageStyle rectangle", "skinparam usecaseBackgroundColor #F5FAF9",
             "skinparam usecaseBorderColor #21323A", "skinparam ArrowColor #52646C",
             "title GiaViên - " + model["title"]]
    aliases = {}
    for key, label in model["actors"].items():
        aliases[key] = "A_"+key
        if key == "auth":
            lines.append(f'actor "{label}" as {aliases[key]} <<abstract>>')
        else:
            lines.append(f'actor "{label}" as {aliases[key]}')
    lines.append('rectangle "Hệ thống đặt bàn và đặt món trước GiaViên" {')
    for i, n in enumerate(model["nodes"]):
        alias = "U_"+str(i)
        aliases[n["id"]] = alias
        label = n["id"]+"\\n"+"\\n".join(n["title"])
        lines.append(f'  usecase "{label}" as {alias}')
    lines.append("}")
    for actor, usecase in model["associations"]:
        lines.append(f'{aliases[actor]} -- {aliases[usecase]}')
    for rel in model["relations"]:
        a, b, kind = rel[:3]
        if kind == "generalization":
            lines.append(f'{aliases[a]} --|> {aliases[b]}')
        else:
            condition = "\\n["+rel[3]+"]" if len(rel) > 3 else ""
            lines.append(f'{aliases[a]} ..> {aliases[b]} : <<{kind}>>{condition}')
    for i, note in enumerate(model["notes"]):
        lines += [f'note as N{i}', note, 'end note']
    lines += ["@enduml", ""]
    return "\n".join(lines)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    PDF.parent.mkdir(parents=True, exist_ok=True)
    for fn in [build_overview, build_customer, build_staff, build_admin, build_vnpay, build_demo]:
        fn()
    c = canvas.Canvas(str(PDF), pagesize=landscape(A3))
    c.setTitle("GiaViên - Bộ sơ đồ UML use-case")
    c.setAuthor("GiaViên project analysis")
    c.setSubject("Use-case tổng quan, khách hàng, nhân viên, quản lý, VNPAY Sandbox và QR demo")
    for model in DIAGRAMS:
        d = model["drawing"]
        # Keep all PDF range separators as simple ASCII hyphens.
        for element in d.items:
            if element["kind"] == "text":
                element["value"] = element["value"].replace("–", "-").replace("—", "-")
        (OUT/(model["stem"]+".svg")).write_text(svg_of(d, model["title"]), encoding="utf-8")
        (OUT/(model["stem"]+".puml")).write_text(plantuml(model), encoding="utf-8")
        c.bookmarkPage(model["stem"])
        c.addOutlineEntry(model["title"], model["stem"], level=0)
        draw_pdf(c, d)
        c.showPage()
    c.save()
    manifest = [{k: v for k, v in m.items() if k != "drawing"} for m in DIAGRAMS]
    (OUT/"model.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Created {len(DIAGRAMS)} SVG drawings and PlantUML sources; PDF: {PDF}")


if __name__ == "__main__":
    main()
