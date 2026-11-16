#!/usr/bin/env python3
"""Opt-in multiple-parent UI/SAF tests; only synthetic notes, original prefs restored."""
import argparse
import re
import unittest
import xml.etree.ElementTree as ET
import device_day_navigation as d
import device_editor_parts as p
import device_editor_exit as e
import device_section_usage as u


def ref(anchor): return f'[[#^{anchor}|{anchor}]]'
def root(label, anchor):
    return (f'{label} ^{anchor}\n> [!info]- Дополнения к событию\n> ```dataviewjs\n'
            f'> await dv.view("Скрипты/Dataview/outLinkInfo", "{anchor}");\n> ```')

TEMPLATE = '## Заметки\n\n## Корни\n\n'
SOURCE = ('## Заметки\n\n' + ref('one') + '\n' + ref('two') + '\nMultiChild\n\n'
          + ref('three') + '\n' + ref('one') + '\nSecondChild\n\n'
          + 'Inline [[#^one|one]]\n\n## Корни\n\n'
          + ref('three') + '\n' + root('RootOne','one') + '\n\n'
          + root('RootTwo','two') + '\n\n' + root('RootThree','three')
          + '\n\nCandidateRoot\n')


def edges(part=0):
    split=p.state()
    return split['links']['edges'].get(split['items'][part]['partId'],[])


def choose_root(query):
    d.wait_for(d.keyboard_shown)
    u.picker_query(query)
    labels=u.labels()
    assert len(labels)==1, labels
    d.tap(d.nodes(d.tree(),text=labels[0])[0])
    p.hide_ime()


class MultipleLinksTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls): d.DayNavigationTest.setUpClass.__func__(cls)
    @classmethod
    def restore(cls): d.DayNavigationTest.restore.__func__(cls)

    def setUp(self):
        d.adb('shell','am','force-stop','ru.obsnotes')
        settings=ET.fromstring(d.preferences())
        assert next(n.text for n in settings if n.get('name')=='vault')==d.URI
        for node in list(settings):
            if node.get('name','').startswith(('editor-','draft-')) and d.FIXTURE in (node.text or ''):
                settings.remove(node)
        d.write_preferences(ET.tostring(settings,encoding='utf-8',xml_declaration=True))
        e.put(self.relative,SOURCE)
        e.put('Шаблоны/Шаблон дневниковой записи.md',TEMPLATE)
        d.adb('shell','am','start','-n','ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(),desc='Поиск разделов')))

    def note(self): return d.adb('exec-out','cat',d.PHONE+'/'+self.relative).decode()
    def stamp(self): return d.adb('shell','stat','-c','%y',d.PHONE+'/'+self.relative)
    def open(self,label):
        d.tap(d.find(text=label,down=False));d.wait_for(d.keyboard_shown);p.hide_ime()
    def save(self):
        d.tap(d.find(desc='Назад',down=False))
        d.wait_for(lambda: bool(d.nodes(d.tree(),desc='Поиск разделов')))
    def restart(self):
        d.adb('shell','am','force-stop','ru.obsnotes')
        d.adb('shell','am','start','-n','ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(),desc='Сохранить')))
        p.hide_ime()

    def test_1_multiple_existing_parents_add_replace_delete_and_recover(self):
        before,stamp=self.note(),self.stamp()
        self.open('MultiChild')
        self.assertEqual('MultiChild',e.input_field().get('text'))
        self.assertIsNotNone(d.find(desc='Связь части 1, 1'))
        self.assertIsNotNone(d.find(desc='Связь части 1, 2'))
        self.save();self.assertEqual(before,self.note());self.assertEqual(stamp,self.stamp())
        self.open('MultiChild')
        d.tap(d.find(text='Связать'));choose_root('RootThree')
        self.assertEqual(['one','two','three'],[x['id'] for x in edges()])
        d.tap(d.find(desc='Связь части 1, 2'));choose_root('CandidateRoot')
        self.assertEqual('one',edges()[0]['id']);self.assertEqual('three',edges()[2]['id'])
        candidate=edges()[1]['id'];self.assertTrue(candidate.startswith('event-'))
        self.restart()
        self.assertEqual(['one',candidate,'three'],[x['id'] for x in edges()])
        d.tap(d.find(desc='Удалить связь части 1, 1'))
        d.wait_for(lambda: len(edges())==2)
        self.assertEqual([candidate,'three'],[x['id'] for x in edges()])
        self.save()
        result=self.note();child=result.split('MultiChild')[0].split('## Заметки\n\n')[1]
        self.assertNotIn('#^one',child);self.assertNotIn('#^two',child)
        self.assertIn('#^'+candidate,child);self.assertIn('#^three',child)
        self.assertIn('CandidateRoot ^'+candidate,result)
        self.assertIn('SecondChild',result);self.assertIn('RootTwo ^two',result)
        print('Existing two parents: clean input, no-op exit, append, replace, trash and recovery passed',flush=True)

    def test_2_delete_root_keeps_own_parent_and_cleans_only_same_file_incoming_links(self):
        d.query('')
        # The root section starts collapsed on the main screen.
        button=d.find(desc='Развернуть раздел Корни',down=False)
        d.tap(button)
        self.open('RootOne')
        self.assertEqual('RootOne',e.input_field().get('text'))
        self.assertIsNotNone(d.find(desc='Корень части 1'))
        d.tap(d.find(desc='Удалить корень части 1'))
        d.wait_for(lambda: bool(d.pending_editor().get('removedRoots')))
        self.assertEqual(['three'],[x['id'] for x in edges()])
        self.assertFalse(d.nodes(d.tree(),desc='Корень части 1'))
        self.restart();self.assertEqual(['three'],[x['id'] for x in edges()])
        self.save()
        result=self.note()
        self.assertNotIn('RootOne ^one',result)
        self.assertNotIn('outLinkInfo", "one"',result)
        self.assertEqual(1,result.count('#^one'),'Only inline reference should remain')
        self.assertIn('Inline [[#^one|one]]',result)
        self.assertIn(ref('two')+'\nMultiChild',result)
        self.assertIn(ref('three')+'\nSecondChild',result)
        self.assertIn(ref('three')+'\nRootOne',result)
        self.assertIn('RootTwo ^two',result);self.assertIn('RootThree ^three',result)
        print('Root trash survived recovery, retained upstream link and removed both incoming metadata links',flush=True)

    def test_3_internal_multiple_parents_and_root_trash_keep_other_edges(self):
        d.tap(d.find(desc='Новая заметка',down=False));d.wait_for(d.keyboard_shown)
        for i,text in enumerate(('FirstPart','SecondPart','ThirdPart')):
            if i: d.adb('shell','input','keyevent','66','66')
            d.adb('shell','input','text',text)
        d.wait_for(lambda: len(p.state()['items'])==3);p.hide_ime()
        for parent in (1,2):
            p.caret(3);d.tap(d.find(text='Связать'))
            d.tap(d.nodes(d.tree(),text=f'К части {parent} · '+('FirstPart','SecondPart')[parent-1])[0])
            d.wait_for(lambda: len(edges(2))==parent)
        self.assertEqual(2,len(edges(2)))
        d.tap(d.find(desc='Удалить корень части 1'))
        d.wait_for(lambda: len(edges(2))==1)
        self.assertEqual(1,len(edges(2)))
        self.assertEqual(p.state()['items'][1]['partId'],edges(2)[0]['part'])
        self.restart();self.assertEqual(1,len(edges(2)))
        self.save();result=self.note()
        self.assertNotRegex(result,r'FirstPart \^event-')
        anchor=re.search(r'SecondPart \^(event-[a-z0-9]+)',result).group(1)
        self.assertIn('#^'+anchor,result)
        print('Internal two-parent graph and independent root removal passed on device',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--serial',required=True);parser.add_argument('--apk')
    args=parser.parse_args()
    d.ADB=['/home/aborunov/Android/Sdk/platform-tools/adb','-s',args.serial];d.APK=args.apk
    class ImmediateResult(unittest.TextTestResult):
        def addFailure(self,test,err):
            super().addFailure(test,err)
            self.stream.writeln(self._exc_info_to_string(err,test))
        def addError(self,test,err):
            super().addError(test,err)
            self.stream.writeln(self._exc_info_to_string(err,test))
    unittest.main(argv=['device_multiple_links'],verbosity=2,
                  testRunner=unittest.TextTestRunner(verbosity=2,resultclass=ImmediateResult))
