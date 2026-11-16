#!/usr/bin/env python3
"""Opt-in frequency regressions using the isolated fixture from device_day_navigation.

Run: python3 tools/device_section_usage.py --serial SERIAL --apk PATH
Original app preferences, editor recovery and history are restored byte-for-byte.
"""
import argparse
import hashlib
import json
import shlex
import unittest
import uuid
import xml.etree.ElementTree as ET
import device_day_navigation as d

KEY = 'section-usage-' + str(uuid.UUID(bytes=hashlib.md5(d.URI.encode()).digest(), version=3))


def history():
    node = next((n for n in ET.fromstring(d.preferences()) if n.get('name') == KEY), None)
    return json.loads(node.text) if node is not None else {}


def note():
    return d.adb('exec-out', 'cat', d.PHONE + '/' + UsageTests.relative).decode()


def type_text(value):
    d.assert_unlocked()
    d.wait_for(d.keyboard_shown)
    fields = [n for n in d.tree().iter('node') if n.get('class') == 'android.widget.EditText']
    assert len(fields) == 1 and fields[0].get('focused') == 'true'
    d.adb('shell', 'input', 'text', value)


def save(payload):
    d.tap(d.find(desc='Назад'))
    d.wait_for(lambda: payload in note())
    d.wait_for(lambda: bool(d.nodes(d.tree(), desc='Поиск разделов')))


def capture():
    d.tap(d.find(desc='Сегодня'))
    d.query('')
    d.tap(d.find(desc='Новая заметка', down=False))


def routes():
    d.tree()  # Wait until the editor/IME transition is complete before hiding the IME.
    if d.keyboard_shown():
        d.adb('shell', 'input', 'keyevent', '4')
    d.tap(d.find(text='Разделы'))


def picker_query(value):
    field = next(n for n in d.tree().iter('node') if n.get('class') == 'android.widget.EditText')
    d.tap(field)
    d.adb('shell', 'input', 'keycombination', '113', '29')
    d.adb('shell', 'input', 'keyevent', '67')
    if value:
        d.adb('shell', 'input', 'text', value)


def labels():
    view = next(n for n in d.tree().iter('node') if n.get('class') == 'android.widget.ListView')
    return [n.get('text') for n in view.iter('node')
            if n.get('class') == 'android.widget.TextView' and n.get('text')]


def dismiss_picker():
    if d.keyboard_shown():
        d.adb('shell', 'input', 'keyevent', '4')
        d.tree()
    d.adb('shell', 'input', 'keyevent', '4')


class UsageTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        d.DayNavigationTest.setUpClass.__func__(cls)
        # Start this fixture with zero history, even after an interrupted old test run.
        d.adb('shell', 'am', 'force-stop', 'ru.obsnotes')
        root = ET.fromstring(d.preferences())
        assert next(n.text for n in root if n.get('name') == 'vault') == d.URI
        for node in list(root):
            if node.get('name') == KEY:
                root.remove(node)
        d.write_preferences(ET.tostring(root, encoding='utf-8', xml_declaration=True))
        d.adb('shell', 'am', 'start', '-n', 'ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(allow_missing=True), desc='Поиск разделов')))

    @classmethod
    def restore(cls):
        d.DayNavigationTest.restore.__func__(cls)

    def test_1_saved_primary_and_additional_assignments_are_counted(self):
        for payload in ('UsageA', 'UsageB'):
            d.tap(d.find(desc='Сегодня'))
            d.query('temperature')
            d.tap(d.find(desc='Новая заметка: Погода'))
            type_text(payload)
            save(payload)
        self.assertEqual({'primary': 2, 'additional': 0}, history()['## Погода'])
        capture()
        type_text('UsageExtra')
        routes()
        picker_query('SportAlias')
        d.tap(d.find(desc='Выбрать: Спорт и диета\nSportAlias'))
        picker_query('WeatherAlias')
        d.tap(d.find(desc='Выбрать: Погода\nWeatherAlias'))
        d.tap(d.find(desc='Применить выбранное'))
        form = d.pending_editor()
        self.assertEqual(['### Спорт и диета', '## Погода'], [r['heading'] for r in form['routes']])
        self.assertEqual({'primary': 2, 'additional': 0}, history()['## Погода'])
        save('UsageExtra')
        d.wait_for(lambda: history().get('## Погода', {}).get('additional') == 1)
        self.assertEqual({'primary': 1, 'additional': 0}, history()['### Спорт и диета'])
        self.assertNotIn('## Заметки', history())

    def test_2_frequency_order_exact_search_all_destinations_and_restart(self):
        before = history()
        capture()
        routes()
        self.assertEqual('Погода\nWeatherAlias', labels()[0])
        picker_query('SportAlias')
        self.assertEqual('Спорт и диета\nSportAlias', labels()[0])
        picker_query('Spacer23')
        self.assertIn('Spacer23', labels())
        dismiss_picker()
        self.assertEqual(before, history())
        d.adb('shell', 'am', 'force-stop', 'ru.obsnotes')
        d.adb('shell', 'am', 'start', '-n', 'ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(allow_missing=True), desc='Назад')))
        self.assertEqual(before, history())
        routes()
        self.assertEqual('Погода\nWeatherAlias', labels()[0])
        dismiss_picker()
        d.tap(d.find(desc='Назад'))
        d.wait_for(lambda: bool(d.nodes(d.tree(), desc='Поиск разделов')))
        self.assertEqual(before, history())

    def test_3_ordinary_edit_and_additional_picker_keep_history_and_frequency(self):
        before = history()
        d.tap(d.find(desc='Сегодня'))
        d.query('temperature')
        d.tap(d.find(desc='Развернуть раздел Погода'))
        d.tap(d.find(text='UsageA'))
        type_text('Suffix')
        save('UsageASuffix')
        self.assertEqual(before, history())
        capture()
        d.tree()
        if d.keyboard_shown():
            d.adb('shell', 'input', 'keyevent', '4')
        d.tap(d.find(text='На строку'))
        self.assertEqual('WeatherAlias · Погода', labels()[0])
        picker_query('SportAlias')
        self.assertEqual('SportAlias · Спорт и диета', labels()[0])
        dismiss_picker()
        d.tap(d.find(desc='Назад'))
        d.wait_for(lambda: bool(d.nodes(d.tree(), desc='Поиск разделов')))
        self.assertEqual(before, history())

    def test_4_failed_save_does_not_learn_and_keeps_external_changes(self):
        before = history()
        d.tap(d.find(desc='Сегодня'))
        d.query('SportAlias')
        d.tap(d.find(desc='Новая заметка: Спорт и диета'))
        type_text('FailedUsage')
        original = note()
        external = original.replace('## Заметки\n', '## Заметки\n\nExternalChange\n', 1)
        path = d.PHONE + '/' + self.relative
        d.adb('shell', 'sh -c ' + shlex.quote('cat > ' + shlex.quote(path)), data=external.encode())
        d.tap(d.find(desc='Назад'))
        d.wait_for(lambda: bool(d.nodes(d.tree(), text='Файл изменился извне')))
        self.assertEqual(before, history())
        d.tap(d.find(text='ОТМЕНИТЬ ПРАВКУ'))
        d.wait_for(lambda: bool(d.nodes(d.tree(), desc='Поиск разделов')))
        self.assertEqual(external, note())
        self.assertEqual(before, history())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default=str(d.Path.home() / 'Android/Sdk/platform-tools/adb'))
    parser.add_argument('--apk')
    args = parser.parse_args()
    d.ADB = [args.adb, '-s', args.serial]
    d.APK = args.apk
    unittest.main(argv=['device_section_usage.py'], verbosity=2, failfast=True)
