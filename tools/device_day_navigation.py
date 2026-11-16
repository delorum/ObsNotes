#!/usr/bin/env python3
"""Opt-in UI regressions on an unlocked phone; writes only an isolated SAF test vault.

Run: python3 tools/device_day_navigation.py --serial SERIAL
Requires an existing persisted SAF grant for Documents/ObsNotes-EditorCheck.
The original app preferences (including pending editor state) are restored on exit.
No production note is opened by this test.
"""
import argparse
import datetime as dt
import re
import shlex
import subprocess
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

FIXTURE = 'ObsNotes-EditorCheck'
PHONE = '/sdcard/Documents/' + FIXTURE
URI = 'content://com.android.externalstorage.documents/tree/primary%3ADocuments%2F' + FIXTURE
ADB = []
APK = None


def adb(*args, data=None):
    return subprocess.check_output(ADB + list(args), input=data, timeout=45)


def preferences():
    return adb('exec-out', 'run-as', 'ru.obsnotes', 'cat', 'shared_prefs/obsnotes.xml')


def write_preferences(data):
    adb('shell', 'run-as ru.obsnotes sh -c ' + shlex.quote('cat > shared_prefs/obsnotes.xml'), data=data)


def assert_unlocked():
    state = adb('shell', 'dumpsys', 'window', 'policy').decode()
    if not re.search(r'\bshowing=false\b', state):
        raise AssertionError('Phone is locked; UI input refused')


def tree():
    assert_unlocked()
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/obsnotes-navigation.xml')
    root = ET.fromstring(adb('exec-out', 'cat', '/data/local/tmp/obsnotes-navigation.xml'))
    if not any(n.get('package') == 'ru.obsnotes' for n in root.iter('node')):
        raise AssertionError('ObsNotes is not visible; UI input refused')
    return root


def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds', ''))))


def nodes(root, text=None, desc=None):
    return [n for n in root.iter('node') if (text is None or n.get('text') == text)
            and (desc is None or n.get('content-desc') == desc)]


def tap(node):
    assert_unlocked()
    x1, y1, x2, y2 = bounds(node)
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def scroll(down=True):
    root = tree()
    view = next(n for n in root.iter('node') if n.get('class') == 'android.widget.ScrollView')
    x1, y1, x2, y2 = bounds(view)
    middle, step = (y1 + y2) // 2, (y2 - y1) // 6
    start, end = (middle + step, middle - step) if down else (middle - step, middle + step)
    adb('shell', 'input', 'swipe', str((x1 + x2) // 2), str(start),
        str((x1 + x2) // 2), str(end), '800')


def find(text=None, desc=None, down=True):
    for _ in range(14):
        root = tree()
        found = nodes(root, text, desc)
        if found:
            return found[0]
        scroll(down)
    raise AssertionError('Control not found: ' + str(text or desc))


def query(value):
    field = find(desc='Поиск разделов', down=False)
    tap(field)
    adb('shell', 'input', 'keycombination', '113', '29')
    adb('shell', 'input', 'keyevent', '67')
    if value:
        adb('shell', 'input', 'text', value)
    adb('shell', 'input', 'keyevent', '4')  # IME only


def wait_for(predicate):
    for _ in range(12):
        if predicate():
            return
        time.sleep(.2)
    raise AssertionError('Expected state did not arrive')


class DayNavigationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        assert_unlocked()
        # A previous fixture must never be silently overwritten or removed.
        exists = adb('shell', 'sh', '-c', shlex.quote('test -e ' + PHONE + ' && echo exists || true'))
        if b'exists' in exists:
            raise AssertionError('Test folder already exists; refusing to replace it')
        adb('shell', 'am', 'force-stop', 'ru.obsnotes')
        cls.original = preferences()
        backup = tempfile.NamedTemporaryFile(prefix='obsnotes-pre-navigation-', suffix='.xml', delete=False)
        backup.write(cls.original)
        backup.close()
        cls.addClassCleanup(cls.restore)
        settings = ET.fromstring(cls.original)
        for node in list(settings):
            if node.get('name') == 'vault' or (node.get('name', '').startswith(('draft-', 'editor-'))
                                               and FIXTURE in (node.text or '')):
                settings.remove(node)
        ET.SubElement(settings, 'string', name='vault').text = URI
        write_preferences(ET.tostring(settings, encoding='utf-8', xml_declaration=True))
        if APK:
            adb('install', '-r', APK)
        cls.today = dt.date.fromisoformat(adb('shell', 'date', '+%F').decode().strip())
        day = cls.today
        names = ['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс']
        months = ['', 'январь', 'февраль', 'март', 'апрель', 'май', 'июнь', 'июль',
                  'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь']
        cls.relative = (f'Дневник/Хроника/{day.year}/{(day.month - 1)//3 + 1}/'
                        f'{day.month:02}-{months[day.month]}/{day.isocalendar().year}-{day.isocalendar().week}-неделя/'
                        f'{day.isoformat()}-{names[day.weekday()]}.md')
        template = '## Заметки\n\nMoveMe\n\nTestMarker\n\n## Чеклист\n\n'
        template += ''.join(f'## Spacer{i:02}\n\n' for i in range(24))
        template += ('## Погода\n```dataviewjs\n'
                     'await dv.view("Скрипты/Dataview/crossPeople", {person: "WeatherAlias"});\n```\n'
                     'Погода:: test\nTemperature:: +10\nТемпература:: +10\n\n'
                     '## Короткая часть\n### Машина\n\n### Спорт и диета\n'
                     '- [ ] #task/diary/diet TestFact\n\n')
        cls.source = template
        with tempfile.TemporaryDirectory(prefix='obsnotes-navigation-fixture-') as temp:
            local = Path(temp) / FIXTURE
            note = local / cls.relative
            note.parent.mkdir(parents=True)
            note.write_text(template)
            path = local / 'Шаблоны/Шаблон дневниковой записи.md'
            path.parent.mkdir(parents=True)
            path.write_text(template)
            rules = local / 'Скрипты/Настройки/Сжатие дневника.md'
            rules.parent.mkdir(parents=True)
            rules.write_text('## Пустые маркеры\n- TestMarker\n')
            adb('push', str(local), '/sdcard/Documents/')
        adb('shell', 'am', 'start', '-n', 'ru.obsnotes/.MainActivity')
        wait_for(lambda: bool(nodes(tree(), desc='Поиск разделов')))

    @classmethod
    def restore(cls):
        adb('shell', 'am', 'force-stop', 'ru.obsnotes')
        write_preferences(cls.original)
        if preferences() != cls.original:
            raise AssertionError('Original preferences failed byte-for-byte restoration')
        adb('shell', 'rm', '-rf', PHONE)

    def test_1_field_key_finds_containing_weather_section(self):
        query('temperature')
        root = tree()
        self.assertEqual(1, len(nodes(root, desc='Развернуть раздел Погода')))
        self.assertFalse(nodes(root, desc='Развернуть раздел Короткая часть'))
        query('')

    def test_2_checklist_navigates_to_nested_section_and_reveals_it(self):
        tap(find(desc='Развернуть раздел Чеклист', down=False))
        tap(find(text='Спорт и диета'))
        wait_for(lambda: bool(nodes(tree(), text='TestFact')))
        root = tree()
        target = nodes(root, desc='Развернуть раздел Спорт и диета')[0]
        viewport = next(n for n in root.iter('node') if n.get('class') == 'android.widget.ScrollView')
        self.assertGreaterEqual(bounds(target)[1], bounds(viewport)[1])
        self.assertLess(bounds(target)[3], bounds(viewport)[3])

    def test_3_task_toggle_keeps_viewport_and_uses_note_date(self):
        before = bounds(find(text='TestFact'))
        tap(find(text='TestFact'))
        path = PHONE + '/' + self.relative
        wait_for(lambda: ('✅ ' + self.today.isoformat()) in adb('exec-out', 'cat', path).decode())
        visible = nodes(tree(), text='TestFact')
        self.assertTrue(visible, 'Task was scrolled out of view')
        after = bounds(visible[0])
        self.assertLessEqual(abs(before[1] - after[1]), 3, 'Task update scrolled the screen')
        tap(find(text='TestFact'))
        wait_for(lambda: '✅' not in adb('exec-out', 'cat', path).decode())
        visible = nodes(tree(), text='TestFact')
        self.assertTrue(visible, 'Task was scrolled out of view')
        self.assertLessEqual(abs(before[1] - bounds(visible[0])[1]), 3)

    def test_4_external_task_update_keeps_viewport(self):
        before = bounds(find(text='TestFact'))
        path = PHONE + '/' + self.relative
        current = adb('exec-out', 'cat', path).decode()
        changed = current.replace('- [ ] #task/diary/diet TestFact',
                                  '- [x] #task/diary/diet TestFact ✅ ' + self.today.isoformat())
        self.assertNotEqual(current, changed)
        adb('shell', 'sh -c ' + shlex.quote('cat > ' + shlex.quote(path)), data=changed.encode())
        wait_for(lambda: bool(nodes(tree(), desc='Снять отметку: TestFact')))
        visible = nodes(tree(), text='TestFact')
        self.assertTrue(visible, 'External update scrolled the task out of view')
        self.assertLessEqual(abs(before[1] - bounds(visible[0])[1]), 3)

    def test_5_moving_a_note_reads_shared_rules_and_compacts_the_actual_file(self):
        tap(find(desc='Сегодня'))
        tap(find(text='MoveMe', down=False))
        tap(find(text='Разделы'))
        field = next(n for n in tree().iter('node') if n.get('class') == 'android.widget.EditText')
        self.assertEqual('true', field.get('focused'))
        adb('shell', 'input', 'text', 'WeatherAlias')
        tap(find(text='Погода\nWeatherAlias'))
        tap(find(desc='Назад'))
        path = PHONE + '/' + self.relative
        wait_for(lambda: 'TestMarker' not in adb('exec-out', 'cat', path).decode())
        result = adb('exec-out', 'cat', path).decode()
        self.assertEqual(1, result.count('MoveMe'))
        self.assertGreater(result.index('MoveMe'), result.index('## Погода'))
        self.assertNotIn('\n\n\n', result)
        self.assertEqual('## Пустые маркеры\n- TestMarker\n',
                         adb('exec-out', 'cat', PHONE + '/Скрипты/Настройки/Сжатие дневника.md').decode())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default=str(Path.home() / 'Android/Sdk/platform-tools/adb'))
    parser.add_argument('--apk', help='Install this APK while the isolated vault is selected')
    args = parser.parse_args()
    ADB = [args.adb, '-s', args.serial]
    APK = args.apk
    unittest.main(argv=['device_day_navigation.py'], verbosity=2, failfast=True)
