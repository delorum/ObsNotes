#!/usr/bin/env python3
"""Opt-in regressions for multiline values and recent UI features in an isolated SAF vault.
Restores the app's original preferences byte-for-byte, including production recovery state.
"""
import argparse
import datetime as dt
import io
import shlex
import unittest
import xml.etree.ElementTree as ET
from PIL import Image
from pathlib import Path
import device_day_navigation as d
import device_editor_parts as p
import device_editor_exit as e
import device_section_usage as u


def cross(heading, ids):
    return heading + '\n```dataviewjs\nawait dv.view("Скрипты/Dataview/crossPeople", {person: "' + ids + '"});\n```\n\n'


def root(label, anchor):
    return (label + ' ^' + anchor + '\n> [!info]- Дополнения к событию\n> ```dataviewjs\n'
            '> await dv.view("Скрипты/Dataview/outLinkInfo", "' + anchor + '");\n> ```')


HIDDEN = ('План на день', 'Важные и/или срочные задачи', 'Сделано сегодня (или отмечено сделанным сегодня)')
TEMPLATE = ('## Заметки\n\n## Поля\nMetric:: old\n\n## Факты\n- [ ] #task/diary/fact Routine\n\n'
            + cross('## Работа', 'WorkAlias') + cross('## Дом', 'HomeAlias')
            + ''.join(cross('## ' + name, alias) for name, alias in zip(HIDDEN, ('PlanAlias', 'UrgentAlias', 'DoneAlias')))
            + cross('## Родитель', 'Parent,ChildrenAlias') + cross('## Дети вместе', 'ChildrenAlias')
            + '## Корни\n')
SOURCE = TEMPLATE.replace('## Заметки\n\n', '## Заметки\n\nOpenMe\n\n') + (
    '\nCandidateRootCommonFirst\n\n' + root('ExistingRootCommonOne', 'one') + '\n\nPhotoFixture\n\n'
    + root('ExistingRootCommonTwo', 'two') + '\n\nCandidateRootCommonLast\n')


def enter(value):
    field = e.input_field()
    d.tap(field)
    d.adb('shell', 'input', 'keycombination', '113', '29')
    d.adb('shell', 'input', 'keyevent', '67')
    for i, line in enumerate(value.split('\n')):
        if i:
            d.adb('shell', 'input', 'keyevent', '66')
        if line:
            d.adb('shell', 'input', 'text', line.replace(' ', '%s'))
    def entered():
        try:
            return d.pending_editor().get('parts', {}).get('value') == value
        except (ET.ParseError, KeyError):
            # SharedPreferences may be between writing and renaming its snapshot.
            return False
    d.wait_for(entered)
    p.hide_ime()


def choose_part(part, alias):
    p.caret(part)
    d.tap(d.find(text='Разделы'))
    d.wait_for(d.keyboard_shown)
    u.picker_query(alias)
    labels = u.labels()
    assert len(labels) == 1, labels
    d.tap(d.nodes(d.tree(), text=labels[0])[0])
    p.hide_ime()


def bind(child, parent):
    p.caret(child)
    d.tap(d.find(text='Связать'))
    root_tree = d.tree()
    target = next(node for node in root_tree.iter('node') if node.get('text', '').startswith(f'К части {parent} · '))
    d.tap(target)
    p.hide_ime()


class ValuePartsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        d.DayNavigationTest.setUpClass.__func__(cls)

    @classmethod
    def restore(cls):
        d.DayNavigationTest.restore.__func__(cls)

    def setUp(self):
        d.adb('shell', 'am', 'force-stop', 'ru.obsnotes')
        settings = ET.fromstring(d.preferences())
        assert next(n.text for n in settings if n.get('name') == 'vault') == d.URI
        for node in list(settings):
            name = node.get('name', '')
            if (name.startswith(('editor-', 'draft-')) and d.FIXTURE in (node.text or '')) or name.startswith('hidden-sections-'):
                settings.remove(node)
        d.write_preferences(ET.tostring(settings, encoding='utf-8', xml_declaration=True))
        e.put(self.relative, SOURCE)
        e.put('Шаблоны/Шаблон дневниковой записи.md', TEMPLATE)
        rules = 'Скрипты/Modules/Дневник/diaryCleanUtils.js'
        parent = d.PHONE + '/' + rules.rsplit('/', 1)[0]
        d.adb('shell', 'mkdir', '-p', parent)
        e.put(rules, 'const excludeLines = ["PhotoFixture"]; const excludeContains = [];\n')
        self.start()

    def start(self):
        d.adb('shell', 'am', 'start', '-n', 'ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(allow_missing=True), desc='Поиск разделов')))

    def note(self):
        return d.adb('exec-out', 'cat', d.PHONE + '/' + self.relative).decode()

    def save(self):
        p.hide_ime()
        d.tap(d.find(desc='Назад', down=False))
        d.wait_for(lambda: bool(d.nodes(d.tree(), desc='Поиск разделов')))

    def open_note(self):
        d.query('')
        d.tap(d.find(text='OpenMe', down=False))
        d.wait_for(d.keyboard_shown)
        p.hide_ime()

    def test_1_multiline_field_parts_tags_links_recovery_and_save(self):
        d.query('Metric')
        d.tap(d.find(desc='Dataview: Metric:: old'))
        d.wait_for(d.keyboard_shown)
        self.assertEqual('old', e.input_field().get('text'))
        enter('one\ntwo\n\nSecond\n\nThird')
        print('Field entered', flush=True)
        self.assertEqual(3, len(p.state()['items']))
        self.assertEqual('Metric:: ', p.state()['items'][0]['fieldPrefix'])
        self.assertEqual('', p.state()['items'][1]['fieldPrefix'])
        choose_part(2, 'WorkAlias')
        choose_part(3, 'HomeAlias')
        print('Independent sections chosen', flush=True)
        p.caret(2)
        d.tap(d.find(text='На строку'))
        u.picker_query('HomeAlias')
        d.tap(d.nodes(d.tree(), text=u.labels()[0])[0])
        p.hide_ime()
        print('Line tag chosen', flush=True)
        bind(2, 1)
        print('First parent selected', flush=True)
        bind(3, 2)
        print('Chain selected', flush=True)
        self.assertEqual(SOURCE, self.note(), 'Editor wrote before exiting')
        d.adb('shell', 'am', 'force-stop', 'ru.obsnotes')
        d.adb('shell', 'am', 'start', '-n', 'ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(), desc='Сохранить')))
        p.hide_ime()
        self.assertEqual('one\ntwo\n\nSecond\n\nThird', p.state()['value'])
        self.assertEqual('Metric:: ', p.state()['items'][0]['fieldPrefix'])
        self.assertIsNotNone(d.find(desc='Dataview-ключ Metric::', down=False))
        self.save()
        text = self.note()
        self.assertIn('Metric:: one; two ^event-', text)
        self.assertIn('Second #crossOneLine/HomeAlias', text)
        self.assertIn('Third', text)
        self.assertGreaterEqual(text.count('outLinkInfo'), SOURCE.count('outLinkInfo') + 2)
        self.assertIn('[[', text)
        print('Phone: multiline field, three independent parts, line tag, chain and recovery passed', flush=True)

    def test_2_multiline_task_addition_and_independent_part(self):
        d.query('Routine')
        d.tap(d.find(desc='Редактировать задачу: Routine'))
        d.wait_for(d.keyboard_shown)
        enter('one\ntwo\n\nTaskSide')
        self.assertTrue(p.state()['items'][0]['taskShell'])
        self.assertFalse(p.state()['items'][1]['taskShell'])
        choose_part(2, 'WorkAlias')
        self.assertEqual(SOURCE, self.note())
        self.save()
        text = self.note()
        self.assertIn('Routine; one; two ✅ ' + self.today.isoformat(), text)
        self.assertIn('TaskSide', text.split('## Работа')[1].split('## Дом')[0])
        self.assertNotIn('Routine; one; two; TaskSide', text)
        print('Phone: task addition collapses only its lines; independent part saves separately', flush=True)

    def test_3_hidden_sections_settings_and_picker_search(self):
        d.query('PlanAlias')
        self.assertFalse(d.nodes(d.tree(), desc='Развернуть раздел ' + HIDDEN[0]))
        d.tap(d.find(desc='Настройки', down=False))
        d.tap(d.find(text='Скрытые разделы · 3'))
        control = d.find(desc='Показывать раздел ' + HIDDEN[0])
        self.assertEqual('false', control.get('checked'))
        d.tap(control)
        d.tap(d.find(desc='Назад', down=False))
        d.tap(d.find(desc='Назад', down=False))
        d.query('PlanAlias')
        self.assertIsNotNone(d.find(desc='Развернуть раздел ' + HIDDEN[0]))
        self.open_note()
        d.tap(d.find(text='Разделы'))
        u.picker_query('PlanAlias')
        self.assertTrue(any(HIDDEN[0] in label for label in u.labels()))
        u.picker_query('UrgentAlias')
        self.assertFalse(u.labels())
        u.dismiss_picker()
        p.hide_ime()
        d.tap(d.find(text='На строку'))
        u.picker_query('ChildrenAlias')
        labels = u.labels()
        self.assertTrue(any('Дети вместе' in label for label in labels), labels)
        u.picker_query('UrgentAlias')
        self.assertFalse(u.labels())
        u.dismiss_picker()
        self.save()
        self.assertEqual(SOURCE, self.note())
        print('Phone: hide/restore, alias search exclusions and complete line section list passed', flush=True)

    def test_4_next_arrow_metadata_probe_updates_without_resetting_view(self):
        root_tree = d.tree()
        arrow = d.nodes(root_tree, desc='Следующий день')[0]
        self.assertEqual('false', arrow.get('enabled'))
        before = d.bounds(d.nodes(root_tree, desc='Поиск разделов')[0])
        tomorrow = p.relative(self.today + dt.timedelta(days=1))
        target = d.PHONE + '/' + tomorrow
        d.adb('shell', 'mkdir', '-p', target.rsplit('/', 1)[0])
        e.put(tomorrow, SOURCE)
        d.wait_for(lambda: d.nodes(d.tree(), desc='Следующий день')[0].get('enabled') == 'true')
        self.assertEqual(before, d.bounds(d.nodes(d.tree(), desc='Поиск разделов')[0]))
        d.adb('shell', 'rm', target)
        d.wait_for(lambda: d.nodes(d.tree(), desc='Следующий день')[0].get('enabled') == 'false')
        self.assertEqual(before, d.bounds(d.nodes(d.tree(), desc='Поиск разделов')[0]))
        print('Phone: next arrow tracks file creation/deletion without moving the screen', flush=True)

    def test_5_existing_roots_precede_candidates_and_placeholders_are_absent(self):
        self.open_note()
        d.tap(d.find(text='Связать'))
        d.wait_for(d.keyboard_shown)
        u.picker_query('RootCommon')
        labels = u.labels()
        self.assertGreaterEqual(len(labels), 2)
        self.assertIn('ExistingRootCommonOne', labels[0])
        self.assertIn('ExistingRootCommonTwo', labels[1])
        u.picker_query('PhotoFixture')
        self.assertFalse(u.labels())
        u.dismiss_picker()
        self.save()
        self.assertEqual(SOURCE, self.note())
        print('Phone: actual roots first, hidden placeholders excluded, unchanged exit preserved file', flush=True)

    def test_6_multiple_protected_fields_noop_and_multiline_update(self):
        d.adb('shell', 'am', 'force-stop', 'ru.obsnotes')
        original = SOURCE.replace('Metric:: old\n', 'Metric:: old\nOther:: keep\n')
        e.put(self.relative, original)
        self.start()
        d.query('Metric')
        stamp = d.adb('shell', 'stat', '-c', '%y', d.PHONE + '/' + self.relative)
        d.tap(d.find(desc='Dataview: Metric:: old'))
        d.wait_for(d.keyboard_shown)
        self.assertEqual('old\n\nkeep', e.input_field().get('text'))
        p.hide_ime()
        self.assertIsNotNone(d.find(desc='Dataview-ключ Other::', down=False))
        self.save()
        self.assertEqual(original, self.note())
        self.assertEqual(stamp, d.adb('shell', 'stat', '-c', '%y', d.PHONE + '/' + self.relative))
        d.tap(d.find(desc='Dataview: Metric:: old'))
        d.wait_for(d.keyboard_shown)
        d.tap(e.input_field())
        d.adb('shell', 'input', 'keycombination', '113', '122')
        d.adb('shell', 'input', 'keycombination', '59', '123')
        d.adb('shell', 'input', 'keyevent', '67')
        d.adb('shell', 'input', 'text', 'one')
        d.adb('shell', 'input', 'keyevent', '66')
        d.adb('shell', 'input', 'text', 'two')
        d.wait_for(lambda: p.state()['value'] == 'one\ntwo\n\nkeep')
        self.assertEqual(['Metric:: ', 'Other:: '], [item['fieldPrefix'] for item in p.state()['items']])
        self.assertEqual(original, self.note())
        self.save()
        self.assertEqual(original.replace('Metric:: old', 'Metric:: one; two'), self.note())
        print('Phone: multiple protected keys, no-op bytes/mtime and multiline value update passed', flush=True)

    def test_7_empty_cards_remain_visible_and_outline_always_follows_cursor(self):
        self.open_note()
        stamp = d.adb('shell', 'stat', '-c', '%y', d.PHONE + '/' + self.relative)

        def card(part, active, empty=False):
            node = d.find(desc=f'Назначения части {part}', down=False)
            self.assertEqual(str(active).lower(), node.get('selected'))
            x1, y1, x2, y2 = d.bounds(node)
            self.assertGreater(x2 - x1, 100)
            self.assertGreaterEqual(y2 - y1, 48)
            picture = Image.open(io.BytesIO(d.adb('exec-out', 'screencap', '-p'))).convert('RGB')
            edge = picture.getpixel((x1 + 1, (y1 + y2) // 2))
            if active:
                self.assertEqual((230, 231, 236), edge, 'Active card has no white outline')
            else:
                self.assertNotEqual((230, 231, 236), edge, 'Inactive card retained the outline')
            if empty:
                self.assertFalse([n for n in node.iter('node') if n.get('text')])
            return node

        card(1, True)
        d.tap(d.find(desc='Удалить раздел: Заметки', down=False))
        card(1, True, True)
        print('Phone: single populated/empty card keeps its white outline', flush=True)

        d.tap(d.find(text='На строку'))
        d.wait_for(d.keyboard_shown)
        u.picker_query('HomeAlias')
        d.tap(d.nodes(d.tree(), text=u.labels()[0])[0])
        p.hide_ime()
        card(1, True)
        d.tap(d.find(desc='Удалить метку HomeAlias', down=False))
        card(1, True, True)

        enter('First\n\nSecond')
        self.assertEqual(2, len(p.state()['items']))
        card(1, False, True)
        card(2, True, True)
        choose_part(2, 'HomeAlias')
        card(2, True)
        d.tap(d.find(desc='Удалить раздел: Дом', down=False))
        card(2, True, True)
        card(1, False, True)
        p.caret(1)
        card(1, True, True)
        card(2, False, True)
        p.caret(2)
        card(2, True, True)
        choose_part(2, 'WorkAlias')
        self.assertEqual([], p.state()['items'][0]['routes'])
        self.assertEqual('## Работа', p.state()['items'][1]['heading'])
        card(1, False, True)
        card(2, True)
        self.assertEqual(SOURCE, self.note(), 'Metadata actions wrote while editor was open')
        d.tap(d.find(desc='Удалить раздел: Работа', down=False))
        card(2, True, True)
        enter('OpenMe')
        card(1, True, True)
        self.save()
        self.assertEqual(SOURCE, self.note())
        self.assertEqual(stamp, d.adb('shell', 'stat', '-c', '%y', d.PHONE + '/' + self.relative))
        print('Phone: empty second card, last-tag deletion, cursor outlines, reassignment and no-op save passed', flush=True)

    def test_8_calendar_status_colors_old_year_navigation_and_dark_arrow(self):
        month_dates = [self.today.replace(day=i) for i in range(1, 29) if i != self.today.day]
        complete, pending, missing_summary, prompt, absent, placeholder = month_dates[:6]
        old = self.today.replace(year=self.today.year - 1, day=1)

        def note(notes, summary):
            return '## Заметки\n\n' + notes + '\n\n## Итог\n' + (
                '' if summary is None else 'ShortDescr:: ' + summary + '\n')

        def put_date(date, text):
            path = p.relative(date)
            d.adb('shell', 'mkdir', '-p', (d.PHONE + '/' + path).rsplit('/', 1)[0])
            e.put(path, text)

        put_date(complete, note('', 'CalendarFilled'))
        put_date(pending, note('CalendarPending', 'Filled'))
        put_date(missing_summary, note('', None))
        put_date(prompt, note('', 'Заполни краткое описание!'))
        put_date(placeholder, note('PhotoFixture', 'Filled'))
        put_date(old, note('', 'OldCalendarFilled'))

        def date_node(date, state=None):
            prefix = 'Дата ' + date.isoformat() + ' · '
            return next((n for n in d.tree().iter('node')
                         if n.get('content-desc', '').startswith(prefix)
                         and (state is None or n.get('content-desc') == prefix + state)), None)

        def ready(date, state):
            d.wait_for(lambda: date_node(date, state) is not None)
            return date_node(date, state)

        def calendar():
            d.tap(d.find(desc='Выбрать дату. Удерживайте, чтобы открыть сегодня', down=False))
            d.wait_for(lambda: bool(d.nodes(d.tree(), desc='Выбрать месяц и год')))

        d.wait_for(lambda: d.nodes(d.tree(), desc='Следующий день')[0].get('enabled') == 'false')
        arrow = d.nodes(d.tree(), desc='Следующий день')[0]
        self.assertGreater(e.pixels(arrow, (89, 97, 112)), 0)
        self.assertEqual(0, e.pixels(arrow, (160, 171, 189)))
        calendar()
        for date, state, color in ((complete, 'Заполнено', (230, 231, 236)),
                                   (pending, 'Нужно разобрать', (239, 115, 124)),
                                   (missing_summary, 'Нужно разобрать', (239, 115, 124)),
                                   (prompt, 'Нужно разобрать', (239, 115, 124)),
                                   (placeholder, 'Заполнено', (230, 231, 236))):
            cell = ready(date, state)
            self.assertEqual('true', cell.get('enabled'))
            self.assertGreater(e.pixels(cell, color), 0, date.isoformat())
        disabled = ready(absent, 'Заметки нет')
        self.assertEqual('false', disabled.get('enabled'))
        d.tap(disabled)
        self.assertIsNotNone(d.find(desc='Выбрать месяц и год'))
        from pathlib import Path
        Path('/tmp/obsnotes-calendar-device.png').write_bytes(d.adb('exec-out', 'screencap', '-p'))
        print('Phone: calendar red/white/disabled date pixels, placeholders and darker arrow passed', flush=True)

        d.tap(d.find(desc='Выбрать месяц и год'))
        tree = d.tree()
        year = next(n for n in tree.iter('node')
                    if n.get('resource-id', '').endswith('/date_picker_header_year'))
        d.tap(year)
        d.tap(d.find(text=str(old.year)))
        ok = next(n for n in d.tree().iter('node') if n.get('resource-id') == 'android:id/button1')
        d.tap(ok)
        older = ready(old, 'Заполнено')
        self.assertGreater(e.pixels(older, (230, 231, 236)), 0)
        d.tap(older)
        d.wait_for(lambda: bool(d.nodes(d.tree(), desc='Поиск разделов')))
        caption = d.nodes(d.tree(), desc='Выбрать дату. Удерживайте, чтобы открыть сегодня')[0].get('text')
        self.assertIn(str(old.year), caption)
        d.query('OldCalendarFilled')
        self.assertIsNotNone(d.find(desc='Dataview: ShortDescr:: OldCalendarFilled'))
        print('Phone: month/year jump and one-tap navigation into an existing older note passed', flush=True)

        d.tap(d.find(desc='Сегодня', down=False))
        d.wait_for(lambda: str(self.today.year) in d.nodes(d.tree(), desc='Выбрать дату. Удерживайте, чтобы открыть сегодня')[0].get('text', ''))
        d.query('')
        calendar()
        selected = ready(complete, 'Заполнено')
        d.adb('shell', 'rm', d.PHONE + '/' + p.relative(complete))
        d.tap(selected)
        d.wait_for(lambda: bool(d.nodes(d.tree(), text='Заметка не найдена')))
        caption = d.nodes(d.tree(), desc='Выбрать дату. Удерживайте, чтобы открыть сегодня')[0].get('text')
        self.assertTrue(caption.startswith(str(self.today.day) + ' '))
        self.assertIn(str(self.today.year), caption)
        put_date(complete, note('NewPending', 'Filled'))
        calendar()
        changed = ready(complete, 'Нужно разобрать')
        self.assertGreater(e.pixels(changed, (239, 115, 124)), 0)
        d.adb('shell', 'input', 'keyevent', '4')
        self.assertEqual(SOURCE, self.note())
        print('Phone: missing-file race retains current note and reopened calendar reloads changed status', flush=True)

    def test_9_value_badges_detach_retarget_and_task_value_recovery(self):
        d.query('Metric')
        d.tap(d.find(desc='Dataview: Metric:: old'))
        d.wait_for(d.keyboard_shown)
        p.hide_ime()
        self.assertIsNotNone(d.find(text='Metric::', down=False))
        d.tap(d.find(desc='Удалить назначение: Metric::', down=False))
        d.wait_for(lambda: not p.state()['items'][0]['fieldPrefix'])
        self.assertEqual('old', p.state()['value'])
        self.assertEqual(SOURCE, self.note())
        self.save()
        self.assertNotIn('Metric:: old', self.note())
        self.assertIn('old', self.note().split('## Поля')[1].split('## Факты')[0])
        print('Phone: Dataview :: badge and trash detach value into its section passed', flush=True)

        d.query('Routine')
        d.tap(d.find(desc='Редактировать задачу: Routine'))
        d.wait_for(d.keyboard_shown)
        self.assertEqual('', d.pending_editor()['text'])
        enter('first\nsecond')
        self.assertIsNotNone(d.find(text='Задача: Routine', down=False))
        Path('/tmp/obsnotes-values-device.png').write_bytes(d.adb('exec-out','screencap','-p'))
        self.save()
        self.assertIn('Routine; first; second ✅ ' + self.today.isoformat(), self.note())
        d.query('Routine')
        d.tap(d.find(desc='Редактировать задачу: Routine; first; second', down=False))
        d.wait_for(d.keyboard_shown)
        self.assertEqual('first; second', e.input_field().get('text'))
        enter('replacement')
        d.adb('shell', 'am', 'force-stop', 'ru.obsnotes')
        d.adb('shell', 'am', 'start', '-n', 'ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(), desc='Сохранить')))
        p.hide_ime()
        self.assertEqual('replacement', p.state()['value'])
        self.assertTrue(p.state()['items'][0]['taskShell'])
        self.save()
        self.assertIn('Routine; replacement ✅ ', self.note())
        self.assertNotIn('first; second', self.note())
        d.query('Routine')
        d.tap(d.find(desc='Редактировать задачу: Routine; replacement', down=False))
        d.wait_for(d.keyboard_shown)
        enter('')
        self.save()
        self.assertIn('Routine ✅ ', self.note())
        self.assertNotIn('replacement', self.note())
        print('Phone: task existing value edit/delete, multiline collapse and recovery passed', flush=True)

        d.query('Routine')
        d.tap(d.find(desc='Редактировать задачу: Routine', down=False))
        d.wait_for(d.keyboard_shown)
        enter('plain')
        d.tap(d.find(desc='Удалить назначение: Задача: Routine', down=False))
        d.wait_for(lambda: not p.state()['items'][0]['taskShell'])
        self.save()
        self.assertNotIn('#task/diary/fact Routine', self.note())
        self.assertIn('plain', self.note().split('## Факты')[1].split('## Работа')[0])
        print('Phone: task trash detaches value into its physical section passed', flush=True)

    def test_10_search_prunes_nested_fields_tasks_and_clear_collapses_everything(self):
        original = SOURCE + ('\n## SearchParent\nParentKey:: ParentOnly\n'
            '### SearchChild\nHitKey:: ExactNeedle\nOtherKey:: UnrelatedValue\n'
            '- [x] #task/diary/fact TargetTask; RiverWalk ✅ ' + self.today.isoformat() + '\n'
            '- [ ] #task/diary/fact SiblingTask\n'
            '### OtherChild\nSiblingKey:: IgnoreMe\n')
        e.put(self.relative, original)
        d.tap(d.find(desc='Обновить', down=False))
        d.query('HitKey')
        tree = d.tree()
        self.assertTrue(d.nodes(tree, desc='Развернуть раздел SearchParent'))
        self.assertTrue(d.nodes(tree, desc='Развернуть раздел SearchChild'))
        self.assertTrue(d.nodes(tree, desc='Dataview: HitKey:: ExactNeedle'))
        for text in ('ParentKey:: ParentOnly', 'OtherKey:: UnrelatedValue', 'SiblingTask', 'SiblingKey:: IgnoreMe'):
            self.assertFalse(d.nodes(tree, text=text), text)
        d.query('rvrwlk')
        tree = d.tree()
        self.assertTrue(d.nodes(tree, desc='Редактировать задачу: TargetTask; RiverWalk'))
        self.assertFalse(d.nodes(tree, desc='Редактировать задачу: SiblingTask'))
        self.assertFalse(d.nodes(tree, desc='Dataview: HitKey:: ExactNeedle'))
        Path('/tmp/obsnotes-search-device.png').write_bytes(d.adb('exec-out','screencap','-p'))
        d.tap(d.find(desc='Снять отметку: TargetTask; RiverWalk'))
        d.wait_for(lambda: '- [ ] #task/diary/fact TargetTask; RiverWalk' in self.note())
        self.assertTrue(d.nodes(d.tree(), desc='Редактировать задачу: TargetTask; RiverWalk'))
        print('Phone: nested matching field/task is visible immediately, siblings absent, toggle retains filter', flush=True)
        d.find(desc='Поиск разделов', down=False)
        d.tap(d.find(desc='Очистить поиск', down=False))
        p.hide_ime()
        tree=d.tree()
        self.assertIn(d.nodes(tree,desc='Поиск разделов')[0].get('text'), ('', 'Поиск разделов'))
        self.assertFalse(d.nodes(tree,desc='Редактировать задачу: TargetTask; RiverWalk'))
        self.assertFalse(d.nodes(tree,desc='Развернуть раздел SearchChild'))
        self.assertFalse(d.nodes(tree,desc='Развернуть текстовый блок'))
        print('Phone: clear query restores full collapsed outline passed', flush=True)

    def test_11_value_badges_can_choose_other_destination_without_losing_text(self):
        d.query('Metric')
        d.tap(d.find(desc='Dataview: Metric:: old'))
        d.wait_for(d.keyboard_shown)
        p.hide_ime()
        d.tap(d.find(text='Metric::', down=False))
        d.wait_for(d.keyboard_shown)
        u.picker_query('Routine')
        labels=u.labels()
        self.assertEqual(1,len(labels),labels)
        d.tap(d.nodes(d.tree(),text=labels[0])[0])
        p.hide_ime()
        self.assertEqual('',p.state()['items'][0]['fieldPrefix'])
        self.assertEqual('TASK',p.state()['items'][0]['kind'])
        self.assertEqual('old',p.state()['value'])
        self.assertIsNotNone(d.find(text='Задача: Routine',down=False))
        self.assertEqual(SOURCE,self.note())
        self.save()
        self.assertIn('Routine; old ✅ ',self.note())
        self.assertNotIn('Metric:: old',self.note())
        d.query('Routine')
        d.tap(d.find(desc='Редактировать задачу: Routine; old'))
        d.wait_for(d.keyboard_shown)
        p.hide_ime()
        d.tap(d.find(text='Задача: Routine',down=False))
        d.wait_for(d.keyboard_shown)
        u.picker_query('WorkAlias')
        labels=u.labels()
        self.assertEqual(1,len(labels),labels)
        d.tap(d.nodes(d.tree(),text=labels[0])[0])
        p.hide_ime()
        self.assertEqual('SECTION',p.state()['items'][0]['kind'])
        self.assertEqual('',p.state()['items'][0]['taskShell'])
        self.assertEqual('## Работа',p.state()['items'][0]['heading'])
        self.assertEqual('old',p.state()['value'])
        self.save()
        self.assertNotIn('Routine',self.note())
        self.assertIn('old',self.note().split('## Работа')[1].split('## Дом')[0])
        print('Phone: tap value badges retargets field to task and task value to ordinary section passed',flush=True)

    def test_12_name_matching_shows_whole_section_and_content_matching_stays_pruned(self):
        original = ('## Заметки\n\nMoveMe\n\n## FamilyGroup\nParentEntry\n\n'
            + cross('### PersonAda','AdaAlias')
            + 'FirstEntry\n\nSecondEntry\n\nMetric:: unrelated\n'
              '- [ ] #task/diary/fact UnrelatedTask\n\n'
              '#### Deep\nDeepEntry\n\n### OtherPerson\nSiblingEntry\n\n'
              '## FactsGroup\nParentOther\n\n### LeafFields\n'
              'TargetKey:: NeedleTarget\nOtherKey:: IgnoreValue\n'
              '- [ ] #task/dairy/fact NeedleTargetTask\n- [ ] #task/dairy/fact IgnoreTask\n')
        d.adb('shell','am','force-stop','ru.obsnotes')
        e.put(self.relative,original)
        e.put('Шаблоны/Шаблон дневниковой записи.md',original)
        self.start()
        for query in ('PersonAda','PersonAd','psnd'):
            d.query(query)
            tree=d.tree()
            self.assertTrue(d.nodes(tree,desc='Развернуть раздел FamilyGroup'))
            self.assertTrue(d.nodes(tree,desc='Развернуть раздел PersonAda'))
            for label in ('FirstEntry','SecondEntry','Metric:: unrelated'):
                self.assertIsNotNone(d.find(text=label))
            self.assertIsNotNone(d.find(desc='Редактировать задачу: UnrelatedTask'))
            self.assertIsNotNone(d.find(desc='Развернуть раздел Deep'))
            self.assertIsNotNone(d.find(text='DeepEntry'))
            tree=d.tree()
            for label in ('ParentEntry','SiblingEntry','ParentOther'):
                self.assertFalse(d.nodes(tree,text=label),label)
            if query=='psnd':
                Path('/tmp/obsnotes-whole-section-device.png').write_bytes(d.adb('exec-out','screencap','-p'))
        print('Phone: exact, literal and fuzzy title matching show all content and descendants; ancestor/sibling content absent',flush=True)
        d.query('ndltrgt')
        tree=d.tree()
        self.assertTrue(d.nodes(tree,desc='Развернуть раздел FactsGroup'))
        self.assertTrue(d.nodes(tree,desc='Развернуть раздел LeafFields'))
        self.assertFalse(d.nodes(tree,desc='Dataview: TargetKey:: NeedleTarget'))
        self.assertTrue(d.nodes(tree,desc='Редактировать задачу: NeedleTargetTask'))
        for label in ('OtherKey:: IgnoreValue','IgnoreTask','ParentOther','FirstEntry'):
            self.assertFalse(d.nodes(tree,text=label),label)
        Path('/tmp/obsnotes-content-only-device.png').write_bytes(d.adb('exec-out','screencap','-p'))
        self.assertEqual(original,self.note())
        print('Phone: content-only fuzzy match keeps just its key/task and ancestor path; source unchanged',flush=True)
        d.query('AdaAlias')
        self.assertTrue(d.nodes(d.tree(),text='Разделы не найдены'))
        d.query('psnd')
        stamp=d.adb('shell','stat','-c','%y',d.PHONE+'/'+self.relative)
        d.tap(d.find(text='FirstEntry'))
        d.wait_for(d.keyboard_shown)
        self.assertEqual('FirstEntry',e.input_field().get('text'))
        self.save()
        self.assertEqual(original,self.note())
        self.assertEqual(stamp,d.adb('shell','stat','-c','%y',d.PHONE+'/'+self.relative))
        self.assertIsNotNone(d.find(text='SecondEntry'))
        d.tap(d.find(desc='Очистить поиск',down=False))
        p.hide_ime()
        tree=d.tree()
        self.assertFalse(d.nodes(tree,desc='Развернуть раздел PersonAda'))
        self.assertFalse(d.nodes(tree,text='FirstEntry'))
        print('Phone: ordinary entry opens from a name result, unchanged exit preserves bytes/mtime, clear collapses all',flush=True)


    def test_13_titles_keys_and_tasks_ignore_plain_text_and_dataview_values(self):
        original = ('## Заметки\n\nMoveMe\n\n'
            + cross('## RedPerson','ChildrenShared') + 'RedEntry\n\n'
            + cross('## BluePerson','ChildrenShared') + 'BlueEntry\n\n'
            + '## Children together\nTogetherEntry\n\n'
              '## FamilyGroup\nParentUnrelated\n\n### LeafKeys\n'
              'Opening context\nNeedle middle\nEnding context\n\n'
              'PlaceMetric:: UniqueValue\nOtherKey:: IgnoreValue\n\n'
              '### Sibling\nSiblingEntry\n\n'
              '## FactsGroup\nField:: FieldOnly\n- [ ] #task/diary/fact Routine; TaskDetail\n')
        d.adb('shell','am','force-stop','ru.obsnotes')
        e.put(self.relative,original)
        e.put('Шаблоны/Шаблон дневниковой записи.md',original)
        self.start()
        stamp=d.adb('shell','stat','-c','%y',d.PHONE+'/'+self.relative)
        for query in ('ndlmdl','Needle','Opening','UniqueValue','unqvl','FieldOnly','ChildrenShared'):
            d.query(query)
            self.assertTrue(d.nodes(d.tree(),text='Разделы не найдены'),query)
        print('Phone: prose, Dataview values and hidden ids do not match exact or fuzzy queries',flush=True)
        d.query('children')
        tree=d.tree()
        headers=[n.get('content-desc') for n in tree.iter('node')
                 if n.get('content-desc','').startswith('Развернуть раздел ')]
        self.assertEqual(['Развернуть раздел Children together'],headers)
        self.assertTrue(d.nodes(tree,text='TogetherEntry'))
        for query in ('PlaceMetric','pmtrc'):
            d.query(query)
            tree=d.tree()
            self.assertTrue(d.nodes(tree,desc='Развернуть раздел FamilyGroup'))
            self.assertTrue(d.nodes(tree,desc='Развернуть раздел LeafKeys'))
            self.assertTrue(d.nodes(tree,desc='Dataview: PlaceMetric:: UniqueValue'))
            self.assertFalse(d.nodes(tree,text='Opening context'))
            self.assertFalse(d.nodes(tree,text='OtherKey:: IgnoreValue'))
        Path('/tmp/obsnotes-key-only-device.png').write_bytes(d.adb('exec-out','screencap','-p'))
        print('Phone: exact/fuzzy key names reveal their current values and ancestors without unrelated prose/fields',flush=True)
        d.query('tskdtl')
        tree=d.tree()
        self.assertTrue(d.nodes(tree,desc='Редактировать задачу: Routine; TaskDetail'))
        self.assertFalse(d.nodes(tree,desc='Dataview: Field:: FieldOnly'))
        d.query('fmlgrp')
        self.assertIsNotNone(d.find(text='ParentUnrelated'))
        self.assertIsNotNone(d.find(text='Opening context'))
        self.assertIsNotNone(d.find(text='SiblingEntry'))
        self.assertEqual(original,self.note())
        self.assertEqual(stamp,d.adb('shell','stat','-c','%y',d.PHONE+'/'+self.relative))
        d.tap(d.find(desc='Очистить поиск',down=False))
        p.hide_ime()
        tree=d.tree()
        self.assertFalse(d.nodes(tree,text='Opening context'))
        self.assertFalse(d.nodes(tree,desc='Развернуть раздел LeafKeys'))
        print('Phone: task text still matches; title matches show full contents, clear collapses; source bytes/mtime unchanged',flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk')
    parser.add_argument('--case', action='append')
    args = parser.parse_args()
    d.ADB = ['/home/aborunov/Android/Sdk/platform-tools/adb', '-s', args.serial]
    d.APK = args.apk
    tests = ['ValuePartsTest.' + case for case in args.case] if args.case else []
    unittest.main(argv=['device_value_parts'] + tests, verbosity=2)
