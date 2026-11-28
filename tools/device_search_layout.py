#!/usr/bin/env python3
"""Opt-in search, task mode, checklist geometry and destination-root checks.
Only synthetic notes in the isolated SAF fixture; preferences restored byte-for-byte.
"""
import argparse
import datetime as dt
import io
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image
import device_day_navigation as d
import device_editor_parts as p
import device_editor_exit as e
import device_section_usage as u


def root(label,anchor):
    return (label+' ^'+anchor+'\n> [!info]- Дополнения к событию\n> ```dataviewjs\n'
            '> await dv.view("Скрипты/Dataview/outLinkInfo", "'+anchor+'");\n> ```')

TEMPLATE=('## Заметки\n\n## Чеклист\n\n## Погода\n```dataviewjs\n'
          'await dv.view("Скрипты/Dataview/crossPeople", {person: "WeatherAlias"});\n```\n'
          'Condition::\nTemperature::\n\n## Факты\n- [ ] #task/diary FixtureFact\n\n'
          '## Питание\n### Перекус 1\n\n## Корни\n\n## Работа\n')
SOURCE=TEMPLATE.replace('## Заметки\n\n','## Заметки\n\nMoveMe\n\n').replace('Condition::\nTemperature::','Condition:: ClearSky\nTemperature:: +17Current').replace('## Корни\n\n','## Корни\n\n'+root('TodayRoot','today-root')+'\n\n')
PAST='## Заметки\n\n'+root('PastRoot','past-root')+'\n\n## Описание\nShortDescr:: PastFixture\n'


class SearchLayoutTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls): d.DayNavigationTest.setUpClass.__func__(cls)
    @classmethod
    def restore(cls): d.DayNavigationTest.restore.__func__(cls)

    def setUp(self):
        d.adb('shell','cmd','statusbar','collapse')
        d.adb('shell','am','force-stop','ru.obsnotes')
        prefs=ET.fromstring(d.preferences())
        for node in list(prefs):
            if node.get('name','').startswith(('editor-','draft-')) and d.FIXTURE in (node.text or ''):prefs.remove(node)
        d.write_preferences(ET.tostring(prefs,encoding='utf-8',xml_declaration=True))
        e.put(self.relative,SOURCE);e.put('Шаблоны/Шаблон дневниковой записи.md',TEMPLATE)
        self.past=p.relative(self.today-dt.timedelta(days=1))
        with tempfile.TemporaryDirectory(prefix='obsnotes-root-date-') as temp:
            directory=Path(temp)/d.FIXTURE
            file=directory/self.past;file.parent.mkdir(parents=True);file.write_text(PAST)
            d.adb('push',str(directory),'/sdcard/Documents/')
        d.adb('shell','am','start','-n','ru.obsnotes/.MainActivity')
        d.wait_for(lambda:bool(d.nodes(d.tree(allow_missing=True),desc='Поиск разделов')))

    def note(self,relative=None):return d.adb('exec-out','cat',d.PHONE+'/'+(relative or self.relative)).decode()
    def open(self):
        d.tap(d.find(text='MoveMe',down=False));d.wait_for(d.keyboard_shown);p.hide_ime()
    def save(self):
        p.hide_ime();d.tap(d.find(desc='Назад',down=False))
        d.wait_for(lambda:bool(d.nodes(d.tree(),desc='Поиск разделов')))
    def task(self):
        d.tap(d.find(text='Разделы'));d.wait_for(d.keyboard_shown);u.picker_query('FixtureFact')
        labels=u.labels();self.assertEqual(1,len(labels));d.tap(d.nodes(d.tree(),text=labels[0])[0]);p.hide_ime()

    def test_1_main_finds_key_names_and_picker_also_finds_values(self):
        before=self.note()
        for query in ('ClearSky','clrsk','17Current'):
            d.query(query)
            self.assertTrue(d.nodes(d.tree(),text='Разделы не найдены'))
        for query,label in [('Condition','Condition:: ClearSky'),('cndtn','Condition:: ClearSky'),('Temperature','Temperature:: +17Current')]:
            d.query(query)
            self.assertIsNotNone(d.find(desc='Dataview: '+label))
            self.assertIsNotNone(d.find(text=label))
        d.tap(d.find(desc='Очистить поиск',down=False));self.open()
        d.tap(d.find(text='Разделы'));d.wait_for(d.keyboard_shown)
        for query in ('Condition','ClearSky','clrsk'):
            u.picker_query(query)
            self.assertIn('Погода / Condition:: ClearSky',u.labels())
        u.picker_query('WeatherAlias');self.assertTrue(any('Погода' in label for label in u.labels()))
        u.dismiss_picker();p.hide_ime();self.save();self.assertEqual(before,self.note())
        print('Main search matches key names only; picker also searches values; fuzzy/alias search preserved; no note writes',flush=True)

    def test_2_task_mode_has_explicit_labels_and_both_save_semantics(self):
        self.open();self.task()
        mode=d.find(text='С текстом');self.assertNotEqual('true',mode.get('checkable'))
        d.tap(mode);d.wait_for(lambda:bool(d.nodes(d.tree(),text='Без текста')))
        self.save();text=self.note()
        self.assertNotIn('MoveMe',text)
        self.assertIn('- [x] #task/diary FixtureFact',text)
        self.assertIn('✅ '+self.today.isoformat(),text)
        self.assertNotIn('FixtureFact; MoveMe',text)
        d.tap(d.find(desc='Новая заметка',down=False));d.wait_for(d.keyboard_shown)
        d.adb('shell','input','text','AddDetails');p.hide_ime();self.task()
        d.tap(d.find(text='С текстом'));d.wait_for(lambda:bool(d.nodes(d.tree(),text='Без текста')))
        d.tap(d.find(text='Без текста'));d.wait_for(lambda:bool(d.nodes(d.tree(),text='С текстом')))
        self.save();self.assertIn('FixtureFact; AddDetails',self.note())
        print('Explicit With text / Without text labels and completion-only/text-appending behavior passed',flush=True)

    def test_3_checklist_cells_share_row_bounds_and_first_text_line(self):
        before=self.note();d.tap(d.find(desc='Развернуть раздел Чеклист',down=False))
        d.wait_for(lambda:bool(d.nodes(d.tree(),desc='Раздел чеклиста Заметки')))
        for label in ('Краткое описание дня','Основные события дня','Питание'):
            left=d.find(desc='Раздел чеклиста '+label)
            root_view=d.tree();right=d.nodes(root_view,desc='Статус чеклиста '+label)[0]
            self.assertEqual(d.bounds(left)[1::2],d.bounds(right)[1::2],label)
        # Fully reveal the wrapped nutrition row before comparing rendered text tops.
        for _ in range(8):
            r=d.tree();left=d.nodes(r,desc='Раздел чеклиста Питание')[0];right=d.nodes(r,desc='Статус чеклиста Питание')[0]
            scroll=next(n for n in r.iter('node') if n.get('class')=='android.widget.ScrollView')
            if d.bounds(right)[3]<d.bounds(scroll)[3]-20 and d.bounds(right)[1]>260:break
            d.scroll(True)
        pic=Image.open(io.BytesIO(d.adb('exec-out','screencap','-p'))).convert('RGB')
        def top(node,colors):
            x1,y1,x2,y2=d.bounds(node)
            return next(y for y in range(y1,y2) if any(pic.getpixel((x,y)) in colors for x in range(x1,x2)))
        self.assertLessEqual(abs(top(left,{(230,231,236)})-top(right,{(239,115,124),(167,205,101)})),2)
        d.tap(right)
        d.wait_for(lambda:bool(d.nodes(d.tree(),desc='Развернуть раздел Питание')))
        self.assertEqual(before,self.note())
        print('Checklist paired cell bounds and wrapped-row text alignment verified by screenshot; navigation preserved',flush=True)

    def test_4_root_picker_uses_selected_existing_day_and_save_keeps_that_target(self):
        self.open();d.tap(d.find(desc='Дата выбранного блока'))
        d.wait_for(lambda:bool(d.nodes(d.tree(),text='Вчера')));d.tap(d.nodes(d.tree(),text='Вчера')[0])
        d.wait_for(lambda:d.pending_editor()['destinationPath']==self.past);p.hide_ime()
        d.tap(d.find(text='Связать'));d.wait_for(d.keyboard_shown);u.picker_query('Root')
        labels=u.labels();self.assertEqual(1,len(labels));self.assertIn('PastRoot',labels[0]);self.assertNotIn('TodayRoot',labels[0])
        d.tap(d.nodes(d.tree(),text=labels[0])[0]);p.hide_ime();self.save()
        destination=self.note(self.past)
        self.assertIn('[['+self.past[:-3]+'#^past-root|PastRoot]]\nMoveMe',destination)
        self.assertIn(root('PastRoot','past-root'),destination)
        self.assertNotIn('MoveMe',self.note(self.relative))
        print('Selected-day root picker and real SAF move link target verified; source-day root was absent',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--serial',required=True);parser.add_argument('--apk')
    args=parser.parse_args();d.ADB=['/home/aborunov/Android/Sdk/platform-tools/adb','-s',args.serial];d.APK=args.apk
    unittest.main(argv=['device_search_layout'],verbosity=2)
