#!/usr/bin/env python3
"""Opt-in relation navigation checks with an isolated vault and restored preferences."""
import argparse
import datetime as dt
import shlex
import unittest

import device_day_navigation as d
import device_editor_exit as edit
import device_editor_parts as parts
from device_multiple_links import root as event_root


def daily_path(day):
    weekdays = ('пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс')
    months = ('январь', 'февраль', 'март', 'апрель', 'май', 'июнь',
              'июль', 'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь')
    week = day.isocalendar()
    return (f'Дневник/Хроника/{day.year}/{(day.month - 1) // 3 + 1}/'
            f'{day.month:02}-{months[day.month - 1]}/{week.year}-{week.week}-неделя/'
            f'{day}-{weekdays[day.weekday()]}.md')


def document(*blocks):
    return '## Заметки\n\n' + '\n\n'.join(blocks) + '\n'


def link(path, anchor, label):
    return f'[[{path.removesuffix(".md")}#^{anchor}|{label}]]\n'


class RelationsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        d.DayNavigationTest.setUpClass.__func__(cls)
        d.adb('shell', 'am', 'force-stop', 'ru.obsnotes')
        cls.paths = {offset: daily_path(cls.today + dt.timedelta(days=offset))
                     for offset in (-2, -1, 0, 1)}
        assert cls.relative == cls.paths[0]
        relation = link(cls.paths[-1], 'fixture-root', 'EventRoot')
        cls.documents = {
            -2: document(relation + event_root('PastChild', 'intermediate')),
            -1: document(event_root('EventRoot', 'fixture-root'), relation + 'LocalChild'),
            0: document(relation + link('', 'another', 'AnotherRoot') + 'TodayChild',
                        event_root('AnotherRoot', 'another')),
            1: document(relation + 'FutureChild'),
        }
        for offset, text in cls.documents.items():
            path = cls.paths[offset]
            d.adb('shell', 'mkdir -p ' + shlex.quote(d.PHONE + '/' + path.rsplit('/', 1)[0]))
            edit.put(path, text)
        edit.put('Шаблоны/Шаблон дневниковой записи.md', document())
        d.adb('shell', 'am', 'start', '-n', 'ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(), text='TodayChild')))

    @classmethod
    def restore(cls):
        d.DayNavigationTest.restore.__func__(cls)

    def note(self, offset):
        return d.adb('exec-out', 'cat', d.PHONE + '/' + self.paths[offset]).decode()

    def editor(self, label, offset):
        d.wait_for(d.keyboard_shown)
        parts.hide_ime()
        self.assertEqual(label, edit.input_field().get('text'))
        self.assertEqual(self.paths[offset], d.pending_editor()['sourcePath'])

    def chain(self):
        d.tap(d.find(desc='Показать связи'))

    def results(self, *ordered):
        d.wait_for(lambda: bool(d.nodes(d.tree(), text=f'Дополнений: {len(ordered) - 1}')))
        tree = d.tree()
        self.assertTrue(d.nodes(tree, text='Связи'))
        self.assertTrue(d.nodes(tree, desc='Назад'))
        results = d.nodes(tree, desc='Список связей')[0]
        self.assertEqual(list(ordered), [n.get('text') for n in results.iter('node')
                                        if n.get('text') in ordered])

    def select(self, label):
        tree = d.tree()
        results = d.nodes(tree, desc='Список связей')
        nodes = d.nodes(results[0] if results else tree, text=label)
        self.assertEqual(1, len(nodes), label)
        d.tap(nodes[0])

    def choose_root(self, label):
        d.tap(d.find(desc='Корень события'))
        tree = d.tree()
        options = [n for n in d.nodes(tree, text=label)
                   if n.get('class') == 'android.widget.CheckedTextView']
        self.assertEqual(1, len(options), label)
        d.tap(options[0])

    def day(self, offset):
        d.wait_for(lambda: bool(d.nodes(d.tree(), desc='Поиск разделов')))
        text = d.find(desc='Выбрать дату. Удерживайте, чтобы открыть сегодня', down=False).get('text')
        day = self.today + dt.timedelta(days=offset)
        self.assertTrue(text.startswith(f'{day.day} ') and str(day.year) in text, text)
        self.select({-1: 'EventRoot', 0: 'TodayChild', 1: 'FutureEdited'}[offset])
        self.editor({-1: 'EventRoot', 0: 'TodayChild', 1: 'FutureEdited'}[offset], offset)

    def test_open_root_and_related_blocks_edit_and_return_to_their_dates(self):
        ordered = ('EventRoot', 'LocalChild', 'PastChild', 'TodayChild', 'FutureChild')
        self.day(0)
        self.chain()
        self.results(*ordered)

        self.choose_root('AnotherRoot')
        self.results('AnotherRoot', 'TodayChild')
        self.choose_root('EventRoot')
        self.results(*ordered)

        self.select('PastChild')
        self.editor('PastChild', -2)
        self.chain()
        self.results(*ordered)  # This child is also a root; reopen the event it came from.

        self.select('EventRoot')
        self.editor('EventRoot', -1)
        self.chain()
        self.results(*ordered)
        d.adb('shell', 'input', 'keyevent', '4')
        self.editor('EventRoot', -1)
        d.adb('shell', 'input', 'keyevent', '4')
        self.day(-1)

        self.chain()
        self.select('FutureChild')
        self.editor('FutureChild', 1)
        edit.type_value('FutureEdited')
        parts.hide_ime()
        d.tap(d.find(desc='Сохранить', down=False))
        self.day(1)
        edit.type_value('FuturePending')
        parts.hide_ime()
        self.chain()
        self.results('EventRoot', 'LocalChild', 'PastChild', 'TodayChild', 'FutureEdited')
        self.select('FutureEdited')
        self.editor('FuturePending', 1)
        self.assertIn('FutureEdited', self.note(1))
        d.tap(d.find(desc='Назад', down=False))
        d.wait_for(lambda: bool(d.nodes(d.tree(), text='FuturePending')))
        self.assertIn('FuturePending', self.note(1))
        self.assertEqual(self.documents[-1], self.note(-1))
        self.assertEqual(self.documents[-2], self.note(-2))
        self.assertEqual(self.documents[0], self.note(0))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk')
    args = parser.parse_args()
    d.ADB = ['/home/aborunov/Android/Sdk/platform-tools/adb', '-s', args.serial]
    d.APK = args.apk
    unittest.main(argv=['device_relations'], verbosity=2)
