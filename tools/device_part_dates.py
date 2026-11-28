#!/usr/bin/env python3
"""Opt-in per-block dates and moved-anchor checks on isolated SAF documents."""
import argparse
import datetime as dt
import shlex
import unittest

import device_day_navigation as d
import device_editor_exit as edit
import device_editor_parts as parts
from device_multiple_links import root
from device_relations import daily_path, document, link


class PartDatesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        d.DayNavigationTest.setUpClass.__func__(cls)
        d.adb('shell', 'am', 'force-stop', 'ru.obsnotes')
        cls.paths = {offset: daily_path(cls.today + dt.timedelta(days=offset))
                     for offset in (-1, 0, 1)}
        cls.external = 'События/Ссылки.md'
        cls.documents = {
            cls.paths[0]: document(root('MovableRoot', 'movable'),
                                   link('', 'movable', 'Local alias') + 'LocalChild',
                                   root('StableRoot', 'stable'),
                                   link('', 'stable', 'Stable alias') + 'MovableChild'),
            cls.paths[-1]: document('Previous day'),
            cls.paths[1]: document(link(cls.paths[0], 'movable', 'Remote alias') + 'RemoteChild'),
            cls.external: 'Other context ' + link(cls.paths[0], 'movable', 'External alias'),
        }
        for path, text in cls.documents.items():
            directory = shlex.quote(d.PHONE + '/' + path.rsplit('/', 1)[0])
            d.adb('shell', 'mkdir -p ' + directory)
            edit.put(path, text)
        edit.put('Шаблоны/Шаблон дневниковой записи.md', document())
        d.adb('shell', 'am', 'start', '-n', 'ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(), text='MovableRoot')))

    @classmethod
    def restore(cls):
        d.DayNavigationTest.restore.__func__(cls)

    def read(self, path):
        return d.adb('exec-out', 'cat', d.PHONE + '/' + path).decode()

    def dates(self, *offsets):
        return [self.paths[offset] for offset in offsets]

    def expect_parts(self, *offsets):
        d.wait_for(lambda: [item['destinationPath'] for item in parts.state()['items']]
                             == self.dates(*offsets))

    def expect_date_label(self, offset):
        expected = (self.today + dt.timedelta(days=offset)).strftime('%d.%m.%y')
        self.assertEqual(expected, d.find(desc='Дата выбранного блока').get('text'))

    def choose_arrow(self, name):
        d.tap(d.find(desc=name))
        d.wait_for(d.keyboard_shown)
        parts.hide_ime()

    def test_choose_dates_move_root_and_move_an_existing_addition(self):
        d.tap(d.find(text='MovableRoot', down=False))
        d.wait_for(d.keyboard_shown)
        for value in ('PastPart', 'StaysHere'):
            d.adb('shell', 'input', 'keyevent', '66', '66')
            d.adb('shell', 'input', 'text', value)
        self.expect_parts(0, 0, 0)
        parts.caret(1)
        self.choose_arrow('На день вперёд')
        self.expect_parts(1, 0, 0)
        self.expect_date_label(1)
        parts.caret(2)
        self.expect_date_label(0)
        d.tap(d.find(desc='Дата выбранного блока'))
        d.tap(d.find(text='Вчера'))
        d.wait_for(d.keyboard_shown)
        parts.hide_ime()
        self.expect_parts(1, -1, 0)
        self.expect_date_label(-1)
        d.tap(d.find(desc='На два дня назад'))  # The day at -3 is absent.
        d.wait_for(lambda: bool(d.nodes(d.tree(), text='Заметка не найдена')))
        self.expect_parts(1, -1, 0)
        self.expect_date_label(-1)
        self.assertIsNotNone(d.find(desc='Дата части 1', down=False))
        self.assertIsNotNone(d.find(desc='Дата части 2'))
        parts.caret(3)
        self.expect_date_label(0)
        print('Cursor, picker, arrows and missing dates act on individual parts', flush=True)
        d.tap(d.find(desc='Сохранить', down=False))
        d.wait_for(lambda: bool(d.nodes(d.tree(), text='StaysHere')))

        today = self.read(self.paths[0])
        past = self.read(self.paths[-1])
        future = self.read(self.paths[1])
        self.assertIn('PastPart', past)
        self.assertIn('MovableRoot ^movable', future)
        self.assertNotIn('MovableRoot ^movable', today)
        self.assertNotIn('PastPart', today)
        self.assertIn(link(self.paths[1], 'movable', 'Local alias') + 'LocalChild', today)
        self.assertIn(link(self.paths[1], 'movable', 'Remote alias') + 'RemoteChild', future)
        self.assertEqual('Other context ' + link(self.paths[1], 'movable', 'External alias'),
                         self.read(self.external))
        print('Moved root, local and remote backlinks, and the retained part verified in SAF', flush=True)

        d.tap(d.find(text='LocalChild', down=False))
        d.wait_for(d.keyboard_shown)
        parts.hide_ime()
        d.tap(d.find(desc='Показать связи'))
        d.wait_for(lambda: bool(d.nodes(d.tree(), text='Дополнений: 2')))
        self.assertTrue(d.nodes(d.tree(), text='MovableRoot'))
        d.tap(d.find(desc='Назад', down=False))
        d.wait_for(d.keyboard_shown)
        parts.hide_ime()
        d.tap(d.find(desc='Назад', down=False))
        d.wait_for(lambda: bool(d.nodes(d.tree(), text='MovableChild')))

        d.tap(d.find(text='MovableChild', down=False))
        d.wait_for(d.keyboard_shown)
        parts.hide_ime()
        self.choose_arrow('На день вперёд')
        self.expect_parts(1)
        d.tap(d.find(desc='Сохранить', down=False))
        d.wait_for(lambda: 'MovableChild' in self.read(self.paths[1]))
        self.assertIn(link(self.paths[0], 'stable', 'Stable alias') + 'MovableChild',
                      self.read(self.paths[1]))
        self.assertNotIn('MovableChild', self.read(self.paths[0]))
        self.assertIn('StableRoot ^stable', self.read(self.paths[0]))
        print('Relationship browser and independent addition move verified', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk')
    args = parser.parse_args()
    d.ADB = ['/home/aborunov/Android/Sdk/platform-tools/adb', '-s', args.serial]
    d.APK = args.apk
    unittest.main(argv=['device_part_dates'], verbosity=2)
