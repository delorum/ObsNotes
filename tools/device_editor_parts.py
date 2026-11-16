#!/usr/bin/env python3
"""Opt-in UI/SAF regression for the shared editor; only a synthetic vault is changed."""
import argparse
import datetime as dt
import re
import tempfile
import unittest
from pathlib import Path
import device_day_navigation as d
import device_section_usage as u


def state(): return d.pending_editor()['parts']


def hide_ime():
    d.tree()
    if d.keyboard_shown():
        d.adb('shell', 'input', 'keyevent', '4')
        d.tree()


def caret(part):
    hide_ime()
    field = d.find(text=state()['value'], down=False)
    d.tap(field)
    d.wait_for(d.keyboard_shown)
    d.adb('shell', 'input', 'keycombination', '113', '122')
    end = state()['items'][part-1]['partEnd']
    if end:
        d.adb('shell', 'input', 'keyevent', *(['22'] * end))
    hide_ime()
    return end


def section_labels(): return [label for label in u.labels() if ' / ' not in label]


def choose(part, query):
    end = caret(part)
    d.tap(d.find(text='Разделы'))
    d.wait_for(d.keyboard_shown)
    u.picker_query(query)
    labels = section_labels()
    if len(labels) != 1:
        raise AssertionError(f'Expected one fixture section for {query}, got {len(labels)}')
    d.tap(d.nodes(d.tree(), text=labels[0])[0])
    return end


def tag_line(part):
    caret(part)
    d.tap(d.find(text='На строку'))
    d.wait_for(d.keyboard_shown)
    u.picker_query('WeatherAlias')
    d.tap(d.nodes(d.tree(), text=u.labels()[0])[0])


def parent(child, parent):
    hide_ime()
    d.tap(d.find(desc=f'Связь части {child}, 1'))
    label = f'К части {parent} · ' + ('FirstPart', 'SecondPart', 'ThirdPart')[parent-1]
    expected_child = state()['items'][child-1]['partId']
    expected_parent = state()['items'][parent-1]['partId']
    d.tap(d.nodes(d.tree(), text=label)[0])
    d.wait_for(lambda: state()['links']['parents'].get(expected_child) == expected_parent)


def relative(day):
    names = ['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс']
    months = ['', 'январь', 'февраль', 'март', 'апрель', 'май', 'июнь', 'июль',
              'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь']
    return (f'Дневник/Хроника/{day.year}/{(day.month-1)//3+1}/{day.month:02}-{months[day.month]}/'
            f'{day.isocalendar().year}-{day.isocalendar().week}-неделя/{day.isoformat()}-{names[day.weekday()]}.md')

def calendar(delta,today):
    hide_ime()
    d.tap(d.find(desc='День для всех частей'))
    d.wait_for(lambda: bool(d.nodes(d.tree(), text='Завтра')))
    d.tap(d.nodes(d.tree(), desc='Выбрать дату')[0])
    future = today + dt.timedelta(days=delta)
    if future.month != today.month:
        root = d.tree()
        next_month = next(node for node in root.iter('node') if node.get('resource-id', '').endswith('/next'))
        d.tap(next_month)
    root = d.tree()
    cells = [node for node in root.iter('node') if node.get('text') == str(future.day)
             or re.match(r'^0?' + str(future.day) + r'\s', node.get('content-desc', ''))]
    if not cells: raise AssertionError('Future date cell missing')
    d.tap(cells[0])
    root = d.tree()
    ok = next(node for node in root.iter('node') if node.get('resource-id')=='android:id/button1')
    d.tap(ok)
    return relative(future)


class EditorPartsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        d.DayNavigationTest.setUpClass.__func__(cls)
        with tempfile.TemporaryDirectory(prefix='obsnotes-relative-days-') as temp:
            local = Path(temp) / d.FIXTURE
            for delta, label in ((-2, 'FixtureTwoDaysAgo'), (-1, 'FixtureYesterday'),
                                 (1, 'FixtureTomorrow'), (2, 'FixtureAfterTomorrow'), (5, 'FixtureFuture')):
                file = local / relative(cls.today + dt.timedelta(days=delta))
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(cls.source + '\nShortDescr:: ' + label + '\n')
            d.adb('push', str(local), '/sdcard/Documents/')

    @classmethod
    def restore(cls): d.DayNavigationTest.restore.__func__(cls)

    def test_common_controls_global_colors_editable_chain_and_existing_dates(self):
        d.tap(d.find(desc='Новая заметка', down=False))
        d.wait_for(d.keyboard_shown)
        for i, text in enumerate(('FirstPart', 'SecondPart', 'ThirdPart')):
            if i:
                d.adb('shell', 'input', 'keyevent', '66', '66')
            d.adb('shell', 'input', 'text', text)
        d.wait_for(lambda: len(state()['items']) == 3)
        print('Three parts entered', flush=True)
        caret(2)
        d.tap(d.find(text='Разделы'))
        d.wait_for(d.keyboard_shown)
        u.picker_query('WeatherAlias')
        d.tap(d.nodes(d.tree(), desc='Выбрать: ' + section_labels()[0])[0])
        u.picker_query('SportAlias')
        d.tap(d.nodes(d.tree(), text=section_labels()[0])[0])
        d.wait_for(lambda: len(state()['items'][1]['routes']) == 2)
        self.assertEqual('## Погода', state()['items'][1]['heading'])
        self.assertEqual('## Заметки', state()['items'][0]['heading'])
        end = choose(3, 'SportAlias')
        d.wait_for(lambda: state()['items'][2]['heading'] == '### Спорт и диета')
        self.assertEqual(end, d.pending_editor()['cursor'])
        tag_line(1)
        tag_line(3)
        hide_ime()
        root = d.tree()
        self.assertEqual(1, len(d.nodes(root, text='Разделы')))
        self.assertFalse(d.nodes(root, text='1 · FirstPart'))
        self.assertFalse(d.nodes(root, text='2 · SecondPart'))
        self.assertFalse(d.nodes(root, text='3 · ThirdPart'))
        date = d.find(desc='День для всех частей')
        sections = d.find(text='Разделы')
        line = d.find(text='На строку')
        self.assertLess(d.bounds(date)[3], d.bounds(sections)[3])
        self.assertEqual(d.bounds(sections)[1], d.bounds(line)[1])
        self.assertGreater(d.bounds(date)[2]-d.bounds(date)[0], d.bounds(sections)[2]-d.bounds(sections)[0])
        # Exact foreground colors in the text area: two tagged paragraphs must differ.
        d.find(text=state()['value'], down=False)
        root = d.tree()
        field = d.nodes(root, text=state()['value'])[0]
        shot = d.adb('exec-out', 'screencap', '-p')
        Path('/tmp/obsnotes-cursor-colors.png').write_bytes(shot)
        from PIL import Image
        import io
        pixels = Image.open(io.BytesIO(shot)).convert('RGB').load()
        x1,y1,x2,y2 = d.bounds(field)
        counts = {color: 0 for color in ((167,205,101), (232,190,99))}
        for y in range(y1,y2):
            for x in range(x1,x2):
                color = pixels[x,y]
                if color in counts: counts[color] += 1
        self.assertTrue(all(count > 20 for count in counts.values()), str(counts))
        print('Cursor controls, chip-only panels and unique font colors verified', flush=True)
        d.tap(d.find(desc='Удалить раздел: Заметки', down=False))
        d.wait_for(lambda: not state()['items'][0]['routes'])
        self.assertFalse(d.nodes(d.tree(), desc='Связь части 1'))
        self.assertFalse(d.nodes(d.tree(),text='Связать части'))
        for child, target in ((2,1),(3,2)):
            caret(child)
            d.tap(d.find(text='Связать'))
            label=f'К части {target} · '+('FirstPart','SecondPart')[target-1]
            d.tap(d.nodes(d.tree(),text=label)[0])
            expected=state()['items'][target-1]['partId']
            child_id=state()['items'][child-1]['partId']
            d.wait_for(lambda: state()['links']['parents'].get(child_id)==expected)
        items = state()['items']
        edges = state()['links']['parents']
        self.assertEqual(items[0]['partId'], edges[items[1]['partId']])
        self.assertEqual(items[1]['partId'], edges[items[2]['partId']])
        hide_ime()
        self.assertFalse(d.nodes(d.tree(),text='Связать части'))
        self.assertIsNotNone(d.find(desc='Корень части 2',down=False))
        self.assertIsNotNone(d.find(desc='Связь части 2, 1',down=False))
        self.assertIsNotNone(d.find(text='К части 2'))
        hide_ime()
        d.tap(d.find(desc='День для всех частей'))
        d.wait_for(lambda: bool(d.nodes(d.tree(), text='Завтра')))
        for label in ('Вчера','Позавчера','Завтра','Послезавтра','FixtureTomorrow'):
            self.assertTrue(d.nodes(d.tree(), text=label), label)
        d.tap(d.nodes(d.tree(), text='Позавчера')[0])
        expected = relative(self.today-dt.timedelta(days=2))
        d.wait_for(lambda: all(item['destinationPath']==expected for item in state()['items']))
        self.assertIsNotNone(d.find(text='Позавчера'))
        chosen = calendar(5,self.today)
        d.wait_for(lambda: all(item['destinationPath']==chosen for item in state()['items']))
        calendar(6,self.today)  # Deliberately absent: no file creation or save-conflict dialog.
        d.wait_for(lambda: bool(d.nodes(d.tree(),text='Заметка не найдена')))
        self.assertTrue(all(item['destinationPath']==chosen for item in state()['items']))
        self.assertFalse(d.nodes(d.tree(),text='Остаться'))
        hide_ime()
        d.tap(d.find(desc='День для всех частей'))
        d.wait_for(lambda: bool(d.nodes(d.tree(), text='Завтра')))
        d.tap(d.nodes(d.tree(), text='Завтра')[0])
        expected = relative(self.today+dt.timedelta(days=1))
        d.wait_for(lambda: all(item['destinationPath']==expected for item in state()['items']))
        self.assertIsNotNone(d.find(text='Завтра'))
        print('Chained links and relative existing past/future days verified', flush=True)
        hide_ime()
        Path('/tmp/obsnotes-cursor-links.png').write_bytes(d.adb('exec-out', 'screencap', '-p'))
        snapshot = state()
        d.adb('shell', 'am', 'force-stop', 'ru.obsnotes')
        d.adb('shell', 'am', 'start', '-n', 'ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(), desc='Сохранить')))
        self.assertEqual(snapshot, state(), 'Restart lost paragraph identities, links or tags')
        hide_ime()
        d.tap(d.find(desc='Сохранить', down=False))
        def note(): return d.adb('exec-out', 'cat', d.PHONE + '/' + expected).decode()
        d.wait_for(lambda: '^event-' in note())
        result = note()
        first = re.search(r'FirstPart[^\n]*\^(event-[A-Za-z0-9_-]+)',result)
        second = re.search(r'SecondPart[^\n]*\^(event-[A-Za-z0-9_-]+)',result)
        self.assertIsNotNone(first)
        self.assertIsNotNone(second)
        self.assertNotEqual(first.group(1),second.group(1))
        self.assertEqual(1,result.count('#^'+first.group(1)))
        self.assertEqual(1,result.count('#^'+second.group(1)))
        self.assertEqual(2,result.count('#crossOneLine/WeatherAlias'))
        self.assertIn('SecondPart #crossPeople/SportAlias',result)
        self.assertGreater(result.index('SecondPart #crossPeople'),result.index('## Погода'))
        self.assertGreater(result.index('ThirdPart'),result.index('### Спорт и диета'))
        self.assertEqual(self.source,d.adb('exec-out','cat',d.PHONE+'/'+self.relative).decode())
        d.wait_for(lambda: bool(d.nodes(d.tree(),desc='Поиск разделов')))
        print('Recovery, future SAF write and actual two-level Markdown links verified',flush=True)

    def test_calendar_handles_missing_without_losing_editor(self):
        d.tap(d.find(desc='Сегодня'))
        d.wait_for(lambda: bool(d.nodes(d.tree(),desc='Поиск разделов')))
        d.tap(d.find(desc='Новая заметка',down=False))
        d.wait_for(d.keyboard_shown)
        d.adb('shell','input','text','CalendarCheck')
        d.wait_for(lambda: state()['value']=='CalendarCheck')
        chosen=calendar(5,self.today)
        d.wait_for(lambda: state()['items'][0]['destinationPath']==chosen)
        calendar(6,self.today)
        d.wait_for(lambda: bool(d.nodes(d.tree(),text='Заметка не найдена')))
        self.assertEqual(chosen,state()['items'][0]['destinationPath'])
        self.assertEqual('CalendarCheck',state()['value'])
        self.assertFalse(d.nodes(d.tree(),text='Файл изменился извне'))
        self.assertFalse(d.nodes(d.tree(),text='Остаться'))
        hide_ime()
        d.tap(d.find(desc='Сохранить',down=False))
        d.wait_for(lambda: 'CalendarCheck' in d.adb('exec-out','cat',d.PHONE+'/'+chosen).decode())
        self.assertEqual(self.source,d.adb('exec-out','cat',d.PHONE+'/'+self.relative).decode())
        print('Existing future calendar date and absent-date recovery verified',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--serial',required=True)
    parser.add_argument('--apk',required=True)
    parser.add_argument('--calendar-only',action='store_true')
    args=parser.parse_args()
    d.ADB=['/home/aborunov/Android/Sdk/platform-tools/adb','-s',args.serial]
    d.APK=args.apk
    name='test_calendar_handles_missing_without_losing_editor' if args.calendar_only else 'test_common_controls_global_colors_editable_chain_and_existing_dates'
    result=unittest.TextTestRunner(verbosity=2).run(unittest.TestSuite([EditorPartsTest(name)]))
    raise SystemExit(not result.wasSuccessful())
