#!/usr/bin/env python3
"""Explicitly requested device check; all edits stay in an isolated SAF fixture.

Restores the app's original preferences (including pending edits) byte-for-byte.
Run: python3 tools/device_editor_parts.py --serial SERIAL --apk PATH
"""
import argparse
import re
import unittest
from pathlib import Path
import device_day_navigation as d
import device_section_usage as u


def state():
    return d.pending_editor()['parts']


def hide_ime():
    d.tree()
    if d.keyboard_shown():
        d.adb('shell', 'input', 'keyevent', '4')
        d.tree()


def choose(part, query):
    hide_ime()
    d.tap(d.find(desc=f'Разделы части {part}'))
    d.wait_for(d.keyboard_shown)
    u.picker_query(query)
    matches = u.labels()
    labels = [label for label in matches if ' / ' not in label]
    if len(labels) != 1:
        raise AssertionError(f'Expected one fixture section for {query}, got {len(labels)}')
    d.tap(d.nodes(d.tree(), text=labels[0])[0])


class EditorPartsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        d.DayNavigationTest.setUpClass.__func__(cls)

    @classmethod
    def restore(cls):
        d.DayNavigationTest.restore.__func__(cls)

    def test_1_all_routes_are_visible_independent_and_recover_after_restart(self):
        d.tap(d.find(desc='Новая заметка', down=False))
        d.wait_for(d.keyboard_shown)
        for i, text in enumerate(('FirstPart', 'SecondPart', 'ThirdPart')):
            if i:
                d.adb('shell', 'input', 'keyevent', '66')
                d.adb('shell', 'input', 'keyevent', '66')
            d.adb('shell', 'input', 'text', text)
        d.wait_for(lambda: len(state()['items']) == 3)
        self.assertEqual('FirstPart\n\nSecondPart\n\nThirdPart', state()['value'])
        hide_ime()
        d.tap(d.find(desc='Разделы части 2'))
        d.wait_for(d.keyboard_shown)
        u.picker_query('WeatherAlias')
        label = u.labels()[0]
        d.tap(d.nodes(d.tree(), desc='Выбрать: ' + label)[0])
        u.picker_query('SportAlias')
        d.tap(d.nodes(d.tree(), text=next(label for label in u.labels() if ' / ' not in label))[0])
        d.wait_for(lambda: len(state()['items'][1]['routes']) == 2)
        self.assertEqual('## Погода', state()['items'][1]['heading'])
        self.assertEqual('## Заметки', state()['items'][0]['heading'])
        self.assertEqual('## Заметки', state()['items'][2]['heading'])
        choose(3, 'SportAlias')
        d.wait_for(lambda: state()['items'][2]['heading'] == '### Спорт и диета')
        self.assertEqual(len(state()['value']), d.pending_editor()['cursor'], 'Section changes moved the text caret')
        hide_ime()
        Path('/tmp/obsnotes-parts-top.png').write_bytes(d.adb('exec-out', 'screencap', '-p'))
        d.tap(d.find(text='Связать части с первой'))
        d.wait_for(lambda: state()['linked'])
        Path('/tmp/obsnotes-parts-bottom.png').write_bytes(d.adb('exec-out', 'screencap', '-p'))
        snapshot = state()
        d.adb('shell', 'am', 'force-stop', 'ru.obsnotes')
        d.adb('shell', 'am', 'start', '-n', 'ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(), desc='Сохранить')))
        self.assertEqual(snapshot, state(), 'Restart lost paragraph assignments')
        hide_ime()
        for i in range(1, 4):
            self.assertIsNotNone(d.find(desc=f'Разделы части {i}', down=i > 1))
        # Save through the real UI and SAF transaction, then verify the synthetic Markdown.
        d.tap(d.find(desc='Сохранить', down=False))
        def note():
            return d.adb('exec-out', 'cat', d.PHONE + '/' + self.relative).decode()
        d.wait_for(lambda: '^event-' in note())
        result = note()
        root = re.search(r'FirstPart \^(event-[A-Za-z0-9_-]+)', result)
        self.assertIsNotNone(root)
        self.assertEqual(2, result.count('#^' + root.group(1)))
        self.assertEqual(1, result.count('SecondPart'))
        self.assertIn('SecondPart #crossPeople/SportAlias', result)
        self.assertGreater(result.index('SecondPart #crossPeople'), result.index('## Погода'))
        self.assertGreater(result.index('ThirdPart'), result.index('### Спорт и диета'))
        self.assertIn('MoveMe', result)
        self.assertIn('TestFact', result)
        d.wait_for(lambda: bool(d.nodes(d.tree(), desc='Поиск разделов')))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', required=True)
    args = parser.parse_args()
    d.ADB = ['/home/aborunov/Android/Sdk/platform-tools/adb', '-s', args.serial]
    d.APK = args.apk
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(EditorPartsTest)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(not result.wasSuccessful())
