import tempfile
import unittest
from pathlib import Path

from core import (
    Database, normalize_gf, extract_order_id, parse_delay_minutes,
    parse_history_summary, parse_detail_fields,
)


class CoreTest(unittest.TestCase):
    def test_parsers(self):
        self.assertEqual(normalize_gf("GF-716"), "GF-716")
        self.assertEqual(extract_order_id("001347203126-C8JTC3BZJAMADA GF-716"), "001347203126-C8JTC3BZJAMADA")
        self.assertEqual(parse_delay_minutes("Delayed by 4 mins"), 4)
        self.assertEqual(parse_delay_minutes("ล่าช้าไป 6 นาที"), 6)
        self.assertEqual(parse_history_summary("คำสั่งซื้อที่เสร็จสมบูรณ์ 39 คำสั่งซื้อที่ยกเลิก 0"), (39, 0))

    def test_detail(self):
        raw = """ลูกค้า\nSomchai\nหมายเหตุจากลูกค้า\nไม่เผ็ด\nสรุปคำสั่งซื้อ\nทั้งหมด\n฿293.00"""
        d = parse_detail_fields(raw, [[['รายการ','ราคา','จำนวน','ราคารวม'],['ข้าว','119.00','1','119.00']]])
        self.assertEqual(d['customer_name'], 'Somchai')
        self.assertEqual(d['customer_note'], 'ไม่เผ็ด')
        self.assertEqual(d['total'], 293.0)
        self.assertTrue(any('ข้าว' in row for row in d['table_rows']))

    def test_database_keeps_unmatched_delay(self):
        with tempfile.TemporaryDirectory() as td:
            db = Database(Path(td) / 'x.db', 'kaprao')
            db.mark_history('001234567890-ABCDEFGH', 'GF-111', 'Delayed by 5 mins', 5)
            db.set_daily_scan('2026-10-04', 1, 0, 1, True)
            row = db.get_order('001234567890-ABCDEFGH')
            self.assertEqual(row['delayed_minutes'], 5)
            self.assertIsNone(row['ready_image'])


if __name__ == '__main__':
    unittest.main()
