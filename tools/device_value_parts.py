#!/usr/bin/env python3
"""Opt-in regressions for multiline values and recent UI features in an isolated SAF vault.
Restores the app's original preferences byte-for-byte, including production recovery state.
"""
import argparse
import datetime as dt
import shlex
import unittest
import xml.etree.ElementTree as ET
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
    d.wait_for(lambda: p.state()['value'] == value)
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
        self.assertIsNotNone(d.find(desc='Dataview-ключ Metric', down=False))
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
        d.tap(d.find(desc='Развернуть раздел Факты'))
        d.tap(d.find(desc='Добавить текст к задаче: Routine'))
        d.wait_for(d.keyboard_shown)
        enter('one\ntwo\n\nTaskSide')
        self.assertTrue(p.state()['items'][0]['taskAppend'])
        self.assertFalse(p.state()['items'][1]['taskAppend'])
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
        self.assertIsNotNone(d.find(desc='Dataview-ключ Other', down=False))
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


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk')
    parser.add_argument('--case')
    args = parser.parse_args()
    d.ADB = ['/home/aborunov/Android/Sdk/platform-tools/adb', '-s', args.serial]
    d.APK = args.apk
    tests = ['ValuePartsTest.' + args.case] if args.case else []
    unittest.main(argv=['device_value_parts'] + tests, verbosity=2)
