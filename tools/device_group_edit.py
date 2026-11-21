#!/usr/bin/env python3
"""Explicitly requested gesture/group-edit checks in a disposable SAF vault, never production notes."""
import argparse
import datetime as dt
import json
import re
import shlex
import time
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
import device_day_navigation as d
import device_editor_parts as p
import device_editor_exit as e
import device_value_parts as v
import device_section_usage as u

TEMPLATE = ('## Заметки\n\n' + v.cross('## Alpha', 'AlphaAlias') + v.cross('## Beta', 'BetaAlias')
            + '## Fields\nMetric:: UniqueValue\n\n## Facts\n- [ ] WearAccessory\n\n## Новости\n\n## Events\n')
SOURCE = TEMPLATE.replace('## Заметки\n\n', '## Заметки\n\nOpenMe\n\n').replace(
    '## Alpha\n', '## Alpha\nOne\n\nTwo\n\nThree\n\n').replace('## Beta\n', '## Beta\nOther\n\n') + (
        '\n' + v.root('Parent', 'parent') + '\n\n[[#^parent|Parent]]\nMiddle\n\nChild\n')


def swipe(node, right=True):
    x1,y1,x2,y2 = d.bounds(node)
    root=d.tree()
    width=max(d.bounds(n)[2] for n in root.iter('node') if n.get('bounds'))
    start=max(x1+15, min(x2-20, width//3))
    end=min(width-35, start+width//3) if right else max(25,start-width//3)
    d.assert_unlocked()
    d.adb('shell','input','swipe',str(start),str((y1+y2)//2),str(end),str((y1+y2)//2),'350')


def choose(options_desc):
    return d.find(desc=options_desc,down=False)


class GroupEditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        d.DayNavigationTest.setUpClass.__func__(cls)

    @classmethod
    def restore(cls):
        d.DayNavigationTest.restore.__func__(cls)

    def setUp(self):
        # A long device run may cross midnight; each app launch opens the current local date.
        self.today=dt.date.fromisoformat(d.adb('shell','date','+%F').decode().strip())
        day=self.today
        names=['пн','вт','ср','чт','пт','сб','вс']
        months=['','январь','февраль','март','апрель','май','июнь','июль','август','сентябрь','октябрь','ноябрь','декабрь']
        self.relative=(f'Дневник/Хроника/{day.year}/{(day.month-1)//3+1}/'
            f'{day.month:02}-{months[day.month]}/{day.isocalendar().year}-{day.isocalendar().week}-неделя/'
            f'{day.isoformat()}-{names[day.weekday()]}.md')
        d.adb('shell','mkdir','-p',d.PHONE+'/'+self.relative.rsplit('/',1)[0])
        d.adb('shell','am','force-stop','ru.obsnotes')
        settings=ET.fromstring(d.preferences())
        for node in list(settings):
            name=node.get('name','')
            if (name.startswith(('editor-','draft-')) and d.FIXTURE in (node.text or '')) or name.startswith('hidden-sections-'):
                settings.remove(node)
        d.write_preferences(ET.tostring(settings,encoding='utf-8',xml_declaration=True))
        e.put(self.relative,SOURCE)
        e.put('Шаблоны/Шаблон дневниковой записи.md',TEMPLATE)
        self.work_path=self.relative.replace('Дневник/Хроника/','Работа/Daily/Хроника/').replace('.md','-work.md')
        d.adb('shell','mkdir','-p',d.PHONE+'/'+self.work_path.rsplit('/',1)[0])
        e.put(self.work_path,'## Ideas\n\nDoNotShow\n\n## Основные события\n\nWorkOne\n\nWorkTwo\n\n## Meetings\nKeepMeeting\n')
        rule='Скрипты/Modules/Дневник/diaryCleanUtils.js'
        d.adb('shell','mkdir','-p',d.PHONE+'/'+rule.rsplit('/',1)[0])
        e.put(rule,'const excludeLines = ["- [ ] WearAccessory"]; const excludeContains = [];\n')
        self.start()

    def start(self):
        d.adb('shell','am','start','-n','ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(allow_missing=True),desc='Поиск разделов')))

    def note(self,path=None):
        return d.adb('exec-out','cat',d.PHONE+'/'+(path or self.relative)).decode()

    def save(self):
        p.hide_ime()
        d.tap(d.find(desc='Назад',down=False))
        d.wait_for(lambda: bool(d.nodes(d.tree(),desc='Поиск разделов')))

    def select(self,text,query='Alpha'):
        d.query(query)
        swipe(d.find(text=text,down=False))
        d.wait_for(lambda: bool(d.nodes(d.tree(),desc='Сбросить выбор')))

    def test_1_selection_survives_search_and_opens_all_parts_with_original_assignments(self):
        self.select('One')
        self.select('Other','Beta')
        d.query('NoMatch')
        tree=d.tree()
        self.assertTrue(d.nodes(tree,text='2'))
        self.assertFalse(d.nodes(tree,text='One'))
        self.assertFalse(d.nodes(tree,desc='Настройки'))
        self.assertFalse(d.nodes(tree,desc='Сегодня'))
        title=d.nodes(tree,text='ObsNotes')[0]
        count=d.nodes(tree,desc='Показать выбранные записи')[0]
        reset=d.nodes(tree,desc='Сбросить выбор')[0]
        a,b,c,z=d.bounds(count); x,y,right,bottom=d.bounds(reset)
        self.assertEqual(c-a,right-x)
        self.assertEqual(z-b,bottom-y)
        self.assertEqual(c-a,z-b)
        self.assertGreater(a,d.bounds(title)[2])
        Path('/tmp/obsnotes-selected-toolbar.png').write_bytes(d.adb('exec-out','screencap','-p'))
        d.tap(d.find(desc='Показать выбранные записи',down=False))
        tree=d.tree()
        self.assertTrue(d.nodes(tree,text='One'))
        self.assertTrue(d.nodes(tree,text='Other'))
        self.assertFalse(d.nodes(tree,text='Two'))
        d.tap(d.find(text='One',down=False))
        d.wait_for(d.keyboard_shown)
        state=d.pending_editor()
        self.assertEqual('One\n\nOther',state['parts']['value'])
        self.assertEqual(['## Alpha','## Beta'],[part['heading'] for part in state['parts']['items']])
        before=self.note()
        self.save()
        self.assertEqual(before,self.note())
        print('Phone: swipe selection, hidden search, two-button toolbar, full group and no-op save passed',flush=True)

    def test_2_merge_across_sections_and_preserve_the_unselected_blocks(self):
        self.select('One')
        self.select('Other','Beta')
        d.tap(d.find(desc='Показать выбранные записи',down=False))
        d.tap(d.find(text='Other',down=False))
        d.wait_for(d.keyboard_shown)
        field=e.input_field()
        d.tap(field)
        d.adb('shell','input','keycombination','113','122')
        d.adb('shell','input','keyevent','22','22','22','22')
        d.adb('shell','input','keyevent','67')
        d.wait_for(lambda: d.pending_editor()['parts']['value']=='One\nOther')
        self.save()
        text=self.note()
        alpha=text.split('## Alpha')[1].split('## Beta')[0]
        beta=text.split('## Beta')[1].split('## Fields')[0]
        self.assertIn('One\nOther',re.sub(r' ?#crossPeople/[A-Za-z]+','',alpha))
        self.assertIn('#crossPeople/BetaAlias',alpha)
        self.assertNotIn('Other',beta)
        self.assertIn('Two',alpha)
        self.assertIn('Three',alpha)
        print('Phone: merging selected originals from different sections retains other blocks and assignments passed',flush=True)

    def test_3_drag_moves_one_physical_block_inside_its_section(self):
        d.query('Alpha')
        source=d.find(text='One',down=False)
        target=d.find(text='Three',down=False)
        x1,y1,x2,y2=d.bounds(source); a,b,c,z=d.bounds(target)
        d.adb('shell','input','draganddrop',str((x1+x2)//2),str((y1+y2)//2),str((a+c)//2),str((b+z)//2),'1200')
        d.wait_for(lambda: self.note().index('Two') < self.note().index('One'))
        text=self.note()
        alpha=text.split('## Alpha')[1].split('## Beta')[0]
        self.assertLess(alpha.index('Two'),alpha.index('Three'))
        self.assertLess(alpha.index('Three'),alpha.index('One'))
        self.assertIn('Other',text.split('## Beta')[1])
        print('Phone: long-press drag reorders physical blocks and keeps adjacent sections passed',flush=True)

    def test_4_plain_task_and_checked_square_chip_and_search_policy(self):
        d.query('Facts')
        d.tap(d.find(desc='Отметить: WearAccessory',down=False))
        d.wait_for(lambda: 'WearAccessory ✅ '+self.today.isoformat() in self.note())
        d.tap(d.find(desc='Редактировать задачу: WearAccessory',down=False))
        d.wait_for(d.keyboard_shown)
        self.assertTrue(d.nodes(d.tree(),desc='Задача: WearAccessory'))
        self.assertFalse(d.nodes(d.tree(),text='Задача: WearAccessory'))
        v.enter('detail')
        self.save()
        self.assertIn('WearAccessory; detail ✅ '+self.today.isoformat(),self.note())
        d.query('UniqueValue')
        self.assertTrue(d.nodes(d.tree(),text='Разделы не найдены'))
        d.query('Metric')
        self.assertTrue(d.nodes(d.tree(),text='Metric:: UniqueValue'))
        print('Phone: untagged excluded task remains editable, date/chip and key-only search passed',flush=True)

    def test_5_work_events_can_be_added_and_other_work_sections_stay_hidden(self):
        d.query('')
        d.tap(d.find(desc='Развернуть раздел Работа'))
        d.tap(d.find(desc='Развернуть раздел Основные события'))
        d.find(text='WorkOne')
        self.assertFalse(d.nodes(d.tree(),text='DoNotShow'))
        d.tap(d.find(desc='Новая заметка: Основные события'))
        d.wait_for(d.keyboard_shown)
        v.enter('NewWorkEvent')
        self.assertFalse(d.nodes(d.tree(),text='На строку'))
        self.assertFalse(d.nodes(d.tree(),text='Связать'))
        self.save()
        self.assertIn('NewWorkEvent',self.note(self.work_path))
        self.assertIn('KeepMeeting',self.note(self.work_path))
        self.assertNotIn('NewWorkEvent',self.note())
        print('Phone: exact work companion, simplified editor and correct destination passed',flush=True)

    def test_6_linked_candidate_can_be_selected_as_an_intermediate_root(self):
        d.query('Events')
        d.tap(d.find(text='Child',down=False))
        d.wait_for(d.keyboard_shown)
        p.hide_ime()
        d.tap(d.find(text='Связать'))
        d.wait_for(d.keyboard_shown)
        u.picker_query('Middle')
        candidates=d.tree()
        label=next(label for label in u.labels() if 'Middle' in label)
        target=d.nodes(d.tree(),text=label)[0]
        d.tap(target)
        p.hide_ime()
        self.save()
        text=self.note()
        middle=text.split('Middle')[1].split('\n\n')[0]
        self.assertIn('^event-',middle)
        self.assertIn('outLinkInfo',middle)
        self.assertIn('[[#^parent|Parent]]\nMiddle',text)
        print('Phone: existing-parent candidate promotion and chain save passed',flush=True)

    def test_7_selected_only_reset_and_left_swipe_still_requires_confirmation(self):
        self.select('One')
        d.tap(d.find(desc='Сбросить выбор',down=False))
        self.assertTrue(d.nodes(d.tree(),desc='Настройки'))
        self.assertFalse(d.nodes(d.tree(),desc='Сбросить выбор'))
        # Deletion is independent of selection and cannot occur from a right swipe.
        self.assertIn('One',self.note())
        source=d.find(text='One',down=False)
        x1,y1,x2,y2=d.bounds(source)
        d.adb('shell','input','swipe',str(x1+(x2-x1)*3//4),str((y1+y2)//2),str(x1+(x2-x1)//4),str((y1+y2)//2),'350')
        tree=d.tree()
        self.assertTrue(any('Удалить' in n.get('text','') for n in tree.iter('node')))
        self.assertIn('One',self.note())
        cancel=next(n for n in tree.iter('node') if n.get('text','').casefold()=='отмена')
        d.tap(cancel)
        self.assertIn('One',self.note())
        print('Phone: reset restores toolbar, swipe directions and deletion confirmation passed',flush=True)

    def test_8_group_recovery_retains_all_originals_and_their_destinations(self):
        self.select('One')
        self.select('Other','Beta')
        d.tap(d.find(desc='Показать выбранные записи',down=False))
        d.tap(d.find(text='One',down=False))
        d.wait_for(d.keyboard_shown)
        v.enter('UpdatedOne\n\nUpdatedOther')
        self.assertEqual(['## Alpha','## Beta'],[part['heading'] for part in d.pending_editor()['parts']['items']])
        d.adb('shell','am','force-stop','ru.obsnotes')
        d.adb('shell','am','start','-n','ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(),desc='Назад')))
        state=d.pending_editor()
        self.assertEqual(2,len(state['batch']))
        self.assertEqual('UpdatedOne\n\nUpdatedOther',state['parts']['value'])
        self.save()
        text=self.note()
        self.assertIn('UpdatedOne',text.split('## Alpha')[1].split('## Beta')[0])
        self.assertIn('UpdatedOther',text.split('## Beta')[1].split('## Fields')[0])
        print('Phone: recovery keeps all original ranges and each part destination passed',flush=True)

    def test_9_projection_can_be_selected_but_cannot_be_dragged(self):
        d.adb('shell','am','force-stop','ru.obsnotes')
        source=SOURCE.replace('\nOne\n','\nOne #crossPeople/BetaAlias\n')
        e.put(self.relative,source)
        self.start()
        d.query('Beta')
        node=d.find(text='One',down=False)
        target=d.find(text='Other',down=False)
        x1,y1,x2,y2=d.bounds(node); a,b,c,z=d.bounds(target)
        d.adb('shell','input','draganddrop',str((x1+x2)//2),str((y1+y2)//2),str((a+c)//2),str((b+z)//2),'1200')
        self.assertEqual(source,self.note())
        self.select('One','Beta')
        d.query('Alpha')
        self.assertTrue(d.nodes(d.tree(),desc='Сбросить выбор'))
        self.assertTrue(d.nodes(d.tree(),text='1'))
        d.tap(d.find(text='One',down=False))
        d.wait_for(d.keyboard_shown)
        state=d.pending_editor()
        self.assertEqual(1,len(state['batch']))
        self.assertEqual('## Alpha',state['parts']['items'][0]['heading'])
        self.save()
        self.assertEqual(source,self.note())
        print('Phone: projections select their original once and refuse reordering passed',flush=True)

    def test_10_merge_child_with_lower_root_keeps_anchor_and_external_links(self):
        d.adb('shell','am','force-stop','ru.obsnotes')
        source=TEMPLATE + ('\n[[#^stable|Root]]\n[[#^extra|Extra]]\nChildTop\n\n'
            + '[[#^upstream|Upstream]]\n' + v.root('RootBody','stable')
            + '\n\n[[#^stable|Root]]\nExternalLeaf\n')
        e.put(self.relative,source)
        self.start()
        self.select('ChildTop','Events')
        self.select('RootBody','Events')
        d.tap(d.find(desc='Показать выбранные записи',down=False))
        d.tap(d.find(text='ChildTop',down=False))
        d.wait_for(d.keyboard_shown)
        d.tap(e.input_field())
        d.adb('shell','input','keycombination','113','122')
        d.adb('shell','input','keyevent',*(['22']*(len('ChildTop')+1)))
        d.adb('shell','input','keyevent','67')
        d.wait_for(lambda: d.pending_editor()['parts']['value']=='ChildTop\nRootBody')
        self.assertEqual('',d.pending_editor()['parts']['error'])
        self.save()
        text=self.note()
        self.assertIn('ChildTop\nRootBody ^stable',text)
        self.assertEqual(1,text.count(' ^stable'))
        self.assertEqual(1,text.count('outLinkInfo'))
        self.assertEqual(1,text.count('[[#^stable|Root]]'))
        self.assertIn('[[#^stable|Root]]\nExternalLeaf',text)
        self.assertIn('[[#^extra|Extra]]',text)
        self.assertIn('[[#^upstream|Upstream]]',text)
        print('Phone: merging child and lower root removes self-link, retains root and external parents/incoming links passed',flush=True)

    def test_11_drag_autoscroll_reaches_blocks_below_the_initial_viewport(self):
        d.adb('shell','am','force-stop','ru.obsnotes')
        body='DragMe\n\n'+'\n\n'.join(f'D{i:02}' for i in range(20))+'\n\n'
        source=SOURCE.replace('One\n\nTwo\n\nThree\n\n',body)
        e.put(self.relative,source)
        self.start()
        d.query('Alpha')
        tree=d.tree()
        viewport=next(n for n in tree.iter('node') if n.get('class')=='android.widget.ScrollView')
        vx,vy,vr,vb=d.bounds(viewport)
        visible=[int(n.get('text')[1:]) for n in tree.iter('node') if re.fullmatch(r'D\d\d',n.get('text','')) and vy <= d.bounds(n)[1] < vb and d.bounds(n)[3]>d.bounds(n)[1]]
        self.assertTrue(visible)
        old_last=max(visible)
        viewport=next(n for n in tree.iter('node') if n.get('class')=='android.widget.ScrollView')
        x1,y1,x2,y2=d.bounds(d.find(text='DragMe',down=False))
        a,b,c,z=d.bounds(viewport)
        x=(x1+x2)//2
        released=False
        try:
            d.adb('shell','input','motionevent','DOWN',str(x),str((y1+y2)//2))
            time.sleep(.7)
            d.adb('shell','input','motionevent','MOVE',str(x),str(z-30))
            time.sleep(1.5)
            d.adb('shell','input','motionevent','MOVE',str(x),str((b+z)//2))
            time.sleep(.3)
            current=d.tree()
            targets=[n for n in current.iter('node') if re.fullmatch(r'D\d\d',n.get('text',''))
                     and int(n.get('text')[1:])>old_last and b+180<d.bounds(n)[1]<z-180]
            if not targets:
                Path('/tmp/obsnotes-drag-autoscroll.png').write_bytes(d.adb('exec-out','screencap','-p'))
                raise AssertionError(f'Autoscroll did not expose later blocks: initially {old_last}, now '+str([n.get('text') for n in current.iter('node') if re.fullmatch(r'D\d\d',n.get('text',''))]))
            target=min(targets,key=lambda n: abs((d.bounds(n)[1]+d.bounds(n)[3])//2-(b+z)//2))
            tx,ty,tr,tb=d.bounds(target)
            d.adb('shell','input','motionevent','MOVE',str((tx+tr)//2),str((ty+tb)//2))
            d.adb('shell','input','motionevent','UP',str((tx+tr)//2),str((ty+tb)//2))
            released=True
            d.wait_for(lambda: self.note().index('DragMe')>self.note().index(f'D{old_last:02}'))
        finally:
            if not released: d.adb('shell','input','motionevent','CANCEL',str(x),str((b+z)//2))
        self.assertIn('Other',self.note())
        print('Phone: drag autoscroll reaches a block below the initial viewport passed',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--serial',required=True)
    parser.add_argument('--apk')
    args,rest=parser.parse_known_args()
    d.ADB=['adb','-s',args.serial]
    d.APK=args.apk
    unittest.main(argv=['device_group_edit.py']+rest,verbosity=2)
