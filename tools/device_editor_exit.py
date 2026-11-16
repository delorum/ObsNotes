#!/usr/bin/env python3
"""Opt-in isolated checks: unchanged exits, caret outline, links and snack checklist.
Restores original app preferences byte-for-byte; never opens production notes.
"""
import argparse
import io
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image
import device_day_navigation as d
import device_editor_parts as p
import device_section_usage as u

TEMPLATE = ('## Заметки\n\n## Чеклист\n\n## Питание\n'
            '### Перекус 1\n\n### Перекус 2\n\n### Перекус 3\n\n### Перекус 4\n\n'
            '## Погода\n```dataviewjs\nawait dv.view("Скрипты/Dataview/crossPeople", {person: "WeatherAlias"});\n```\n\n'
            '### Спорт\n```dataviewjs\nawait dv.view("Скрипты/Dataview/crossPeople", {person: "SportAlias"});\n```\n\n'
            '## Внешний корень\n\nExternalRoot ^fixture-root\n'
            '> [!info]- Дополнения к событию\n> ```dataviewjs\n'
            '> await dv.view("Скрипты/Dataview/outLinkInfo", "fixture-root");\n> ```\n')
SOURCE = TEMPLATE.replace('## Заметки\n\n', '## Заметки\n\nOpenMe  #crossPeople/WeatherAlias  \n\n')
RULES = '## Пустые маркеры\n- TestMarker\n'


def put(relative, text):
    with tempfile.TemporaryDirectory(prefix='obsnotes-exit-') as temp:
        file = Path(temp) / 'fixture.md'
        file.write_text(text)
        d.adb('push', str(file), d.PHONE + '/' + relative)


def note(): return d.adb('exec-out', 'cat', d.PHONE + '/' + ExitTest.relative).decode()
def modified(): return d.adb('shell', 'stat', '-c', '%y', d.PHONE + '/' + ExitTest.relative)


def input_field():
    fields=[node for node in d.tree().iter('node') if node.get('class')=='android.widget.EditText' or node.get('class','').endswith('CursorEditText')]
    if len(fields)!=1: raise AssertionError('Expected exactly one fixture text field')
    return fields[0]


def type_value(value):
    field = input_field()
    d.tap(field)
    d.adb('shell', 'input', 'keycombination', '113', '29')
    d.adb('shell', 'input', 'keyevent', '67')
    d.adb('shell', 'input', 'text', value.replace(' ', '%s'))
    d.wait_for(lambda: d.pending_editor().get('parts',{}).get('value') == value)


def pixels(node, color):
    picture = Image.open(io.BytesIO(d.adb('exec-out', 'screencap', '-p'))).convert('RGB')
    x1,y1,x2,y2 = d.bounds(node)
    data = picture.load()
    return sum(data[x,y] == color for y in range(y1,y2) for x in range(x1,x2))


class ExitTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        d.DayNavigationTest.setUpClass.__func__(cls)
        d.adb('shell', 'am', 'force-stop', 'ru.obsnotes')
        put(cls.relative, SOURCE)
        put('Шаблоны/Шаблон дневниковой записи.md', TEMPLATE)
        d.adb('shell', 'am', 'start', '-n', 'ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(), desc='Поиск разделов')))

    @classmethod
    def restore(cls): d.DayNavigationTest.restore.__func__(cls)

    def setUp(self):
        if self._testMethodName.startswith('test_1'): return
        # Each scenario starts independently, even if an earlier assertion failed.
        d.adb('shell','am','force-stop','ru.obsnotes')
        settings=ET.fromstring(d.preferences())
        assert next(n.text for n in settings if n.get('name')=='vault')==d.URI
        for node in list(settings):
            if node.get('name','').startswith(('editor-','draft-')) and d.FIXTURE in (node.text or ''):
                settings.remove(node)
        d.write_preferences(ET.tostring(settings,encoding='utf-8',xml_declaration=True))
        put(self.relative,SOURCE)
        put('Скрипты/Настройки/Сжатие дневника.md',RULES)
        d.adb('shell','am','start','-n','ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(),desc='Поиск разделов')))

    def open_existing(self):
        d.tap(d.find(text='OpenMe', down=False))
        d.wait_for(d.keyboard_shown)
        p.hide_ime()

    def exit_without_write(self, before, stamp):
        # A no-op exit must bypass even the cleanup/journal pipeline.
        put('Скрипты/Настройки/Сжатие дневника.md', '# intentionally invalid rules\n')
        p.hide_ime()
        d.tap(d.find(desc='Назад', down=False))
        d.wait_for(lambda: bool(d.nodes(d.tree(), desc='Поиск разделов')))
        self.assertEqual(before, note())
        self.assertEqual(stamp, modified(), 'A no-op exit rewrote the Markdown file')
        put('Скрипты/Настройки/Сжатие дневника.md', RULES)

    def test_1_unchanged_and_reverted_text_tags_and_root_do_not_write(self):
        before, stamp = note(), modified()
        self.open_existing()
        self.exit_without_write(before, stamp)
        self.open_existing()
        original = input_field().get('text','')
        type_value('Changed')
        type_value(original)
        self.exit_without_write(before, stamp)
        print('Untouched and text-reverted exits preserved bytes and modification time', flush=True)
        self.open_existing()
        p.hide_ime()
        d.tap(d.find(text='На строку'))
        d.wait_for(d.keyboard_shown)
        u.picker_query('SportAlias')
        d.tap(d.nodes(d.tree(), text=u.labels()[0])[0])
        p.hide_ime()
        d.tap(d.find(desc='Удалить метку SportAlias'))
        self.exit_without_write(before, stamp)
        self.open_existing()
        d.tap(d.find(text='Сделать корневым'))
        p.hide_ime()
        d.tap(d.find(desc='Связь части 1'))
        d.tap(d.nodes(d.tree(), text='Без родителя')[0])
        self.exit_without_write(before, stamp)
        print('Tag and root round trips also exited without writes', flush=True)

    def test_2_outline_follows_caret_and_links_work_without_checkbox(self):
        d.tap(d.find(desc='Новая заметка', down=False))
        d.wait_for(d.keyboard_shown)
        for i, text in enumerate(('FirstPart','SecondPart','ThirdPart')):
            if i: d.adb('shell','input','keyevent','66','66')
            d.adb('shell','input','text',text)
        d.wait_for(lambda: len(p.state()['items']) == 3)
        p.hide_ime()
        self.assertFalse(d.nodes(d.tree(), text='Связать части'))
        for part in (1,2,3):
            p.caret(part)
            root=d.tree()
            for i in (1,2,3):
                card=d.nodes(root,desc=f'Назначения части {i}')[0]
                self.assertEqual(str(i==part).lower(),card.get('selected'))
            active=d.nodes(root,desc=f'Назначения части {part}')[0]
            picture=Image.open(io.BytesIO(d.adb('exec-out','screencap','-p'))).convert('RGB')
            x1,y1,x2,y2=d.bounds(active)
            self.assertEqual((230,231,236),picture.getpixel((x1+1,(y1+y2)//2)))
        p.caret(2)
        d.tap(d.find(text='Связать'))
        d.tap(d.nodes(d.tree(),text='К части 1 · FirstPart')[0])
        d.wait_for(lambda: len(p.state()['links']['parents'])==1)
        p.caret(3)
        d.tap(d.find(text='Связать'))
        d.tap(d.nodes(d.tree(),text='К части 2 · SecondPart')[0])
        d.wait_for(lambda: len(p.state()['links']['parents'])==2)
        p.caret(3)
        d.tap(d.find(text='Связать'))
        d.tap(d.nodes(d.tree(),text='Из другой заметки…')[0])
        d.wait_for(d.keyboard_shown)
        u.picker_query('ExternalRoot')
        d.tap(d.nodes(d.tree(),text=u.labels()[0])[0])
        d.wait_for(lambda: '#^fixture-root' in p.state()['items'][2]['text'])
        p.hide_ime()
        d.tap(d.find(desc='Связь части 3'))
        d.tap(d.nodes(d.tree(),text='К части 2 · SecondPart')[0])
        d.wait_for(lambda: len(p.state()['links']['parents'])==2)
        p.hide_ime()
        Path('/tmp/obsnotes-active-part.png').write_bytes(d.adb('exec-out','screencap','-p'))
        d.tap(d.find(desc='Сохранить',down=False))
        d.wait_for(lambda: 'ThirdPart' in note())
        self.assertEqual(2,note().count('#^event-'))
        print('White caret outline and two-level links without checkbox verified',flush=True)

    def test_3_numbered_snacks_are_green_only_when_filled(self):
        base=note()
        d.tap(d.find(desc='Развернуть раздел Чеклист',down=False))
        status='Завтрак | Обед | Ужин | Перекусы'
        def green():
            node=d.find(text=status)
            return pixels(node,(167,205,101))>20
        self.assertFalse(green())
        for n in range(1,5):
            if n>1:
                put(self.relative,base)
                d.wait_for(lambda: not green())
            put(self.relative,base.replace(f'### Перекус {n}\n',f'### Перекус {n}\nSnackFilled{n}\n'))
            d.wait_for(green)
            print(f'Snack {n} rendered green',flush=True)
        Path('/tmp/obsnotes-snacks-checklist.png').write_bytes(d.adb('exec-out','screencap','-p'))


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--serial',required=True)
    parser.add_argument('--apk',required=True)
    parser.add_argument('--test',choices=('exit','outline','snacks'))
    args=parser.parse_args()
    d.ADB=['/home/aborunov/Android/Sdk/platform-tools/adb','-s',args.serial]
    d.APK=args.apk
    names={'exit':'test_1_unchanged_and_reverted_text_tags_and_root_do_not_write',
           'outline':'test_2_outline_follows_caret_and_links_work_without_checkbox',
           'snacks':'test_3_numbered_snacks_are_green_only_when_filled'}
    suite=unittest.TestSuite([ExitTest(names[args.test])]) if args.test else unittest.defaultTestLoader.loadTestsFromTestCase(ExitTest)
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(not result.wasSuccessful())
