#!/usr/bin/env python3
"""Opt-in encryption UI/SAF regressions. Only synthetic secrets in an isolated test vault.
Original preferences are restored byte-for-byte, including existing password settings.
"""
import argparse
import base64
import hashlib
import json
import re
import subprocess
import time
import unittest
import uuid
import xml.etree.ElementTree as ET
import device_day_navigation as d
import device_editor_parts as p
import device_editor_exit as e

PASSWORD='FixturePassword!2026'
PIN='FixtureAccessKey!2026'
NEWPIN='ChangedAccessKey!2026'
MULTI_CLEAR='FixtureSecret\nSecondLine\n\nLastParagraph'
CLEAR='FixtureSecret\nSecondLine\nLastParagraph'
MULTI_CIPHER='h6VlaAwU8ufUmGSt/8LmRKOlM1DeqSB4VMvrzcMBx9BrKJyudVi0AWkdsq9nd4cyNtBP6Ssxk2yGxLHWRFyQ8x/d9T0PF+nLuW+nFPwxbhXR90Xpb3t+'
CIPHER='Y9sbDGGJvl35FBa/KrROAMeQ2pMYTzn2qUSeC/PuKOPhWeZ4dcR455ppqwqGovhjnEB3pRPbz5e6y1ZupMXjRQdeCvKubr6/sUxIUGDFxjkw1xAspeo='
PLACEHOLDER='⟦Зашифровано⟧'
SOURCE='## Заметки\n\nPublicOne\n\n`secret '+CIPHER+'`\n\n## Работа\n\nTail\n'
TEMPLATE='## Заметки\n\n## Работа\n\n'
KEY='crypto-password-'+str(uuid.UUID(bytes=hashlib.md5(d.URI.encode()).digest(),version=3))
PLUGIN=None


def enter(desc,value):
    print('Input: '+desc,flush=True)
    d.tap(d.find(desc=desc))
    d.adb('shell','input','keycombination','113','29')
    d.adb('shell','input','keyevent','67')
    d.adb('shell','input','text',value.replace(' ','%s'))


def press(text):
    print('Action: '+text,flush=True)
    root=d.tree()
    button=next((n for n in root.iter('node') if n.get('resource-id')=='android:id/button1'),None)
    if text in ('Сохранить','Открыть','Удалить') and button is not None:
        d.tap(button)
    else:
        d.tap(d.find(text=text))
    d.wait_for(lambda: any(n.get('package')=='ru.obsnotes' for n in d.tree(allow_missing=True).iter('node')))


def _plugin(mode,payload,password):
    code=r'''
const fs=require('fs'),vm=require('vm');const code=fs.readFileSync(process.argv[1],'utf8');
const c={crypto:require('crypto').webcrypto,TextEncoder,TextDecoder,btoa,atob};vm.createContext(c);
vm.runInContext(code.slice(code.indexOf('var CryptoFactory = class'),code.indexOf('// src/UiHelper.ts'))+';globalThis.factory=new CryptoFactory()',c);
const [mode,cipher,password]=JSON.parse(fs.readFileSync(0,'utf8'));
c.factory[mode](cipher,password).then(value=>console.log(JSON.stringify(value)));
'''
    return json.loads(subprocess.check_output(['node','-e',code,PLUGIN],input=json.dumps([mode,payload,password]).encode(),timeout=30))


def decrypt_plugin(payload,password):
    return _plugin('decryptFromBase64',payload,password)


def editor_field():
    # Metadata searches can scroll the field partly behind the toolbar. Do not tap that fragment.
    d.adb('shell','cmd','statusbar','collapse')
    for _ in range(10):
        root=d.tree()
        fields=[n for n in root.iter('node') if n.get('class')=='android.widget.EditText' or n.get('class','').endswith('CursorEditText')]
        back=d.nodes(root,desc='Назад')
        if len(fields)==1 and back and d.bounds(fields[0])[1]>d.bounds(back[0])[3]:
            return fields[0]
        d.scroll(False)
    raise AssertionError('Editor input was not fully visible below the toolbar')


def wrapped():
    return next((node.text for node in ET.fromstring(d.preferences()) if node.get('name')==KEY),None)


def editor_envelope():
    for node in ET.fromstring(d.preferences()):
        if node.get('name','').startswith('editor-') and d.FIXTURE in (node.text or ''):
            return json.loads(node.text)
    raise AssertionError('Missing isolated recovery snapshot')


class EncryptionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        d.DayNavigationTest.setUpClass.__func__(cls)

    def setUp(self):
        d.adb('shell','cmd','statusbar','collapse')
        d.adb('shell','am','force-stop','ru.obsnotes')
        prefs=ET.fromstring(d.preferences())
        for node in list(prefs):
            if node.get('name')==KEY or (node.get('name','').startswith(('editor-','draft-')) and d.FIXTURE in (node.text or '')):
                prefs.remove(node)
        d.write_preferences(ET.tostring(prefs,encoding='utf-8',xml_declaration=True))
        e.put(self.relative,SOURCE.replace(CIPHER,MULTI_CIPHER) if self._testMethodName.startswith('test_y') else SOURCE);e.put('Шаблоны/Шаблон дневниковой записи.md',TEMPLATE)
        d.adb('shell','am','start','-n','ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(),desc='Поиск разделов')))

    @classmethod
    def restore(cls): d.DayNavigationTest.restore.__func__(cls)

    def note(self): return d.adb('exec-out','cat',d.PHONE+'/'+self.relative).decode()
    def stamp(self): return d.adb('shell','stat','-c','%y',d.PHONE+'/'+self.relative)
    def main(self): d.wait_for(lambda: bool(d.nodes(d.tree(),desc='Поиск разделов')))
    def open(self,text):
        d.tap(d.find(text=text,down=False));d.wait_for(d.keyboard_shown);p.hide_ime()
    def exit(self):
        d.tap(d.find(desc='Назад',down=False));self.main()
    def settings(self):
        d.tap(d.find(desc='Настройки',down=False));d.wait_for(lambda: bool(d.nodes(d.tree(),text='Шифрование')))
    def back_settings(self): d.tap(d.find(desc='Назад',down=False));self.main()
    def unlock(self,pin=PIN):
        d.wait_for(lambda: bool(d.nodes(d.tree(),text='Пинкод шифрования')))
        d.wait_for(d.keyboard_shown)
        field=d.find(desc='Пинкод')
        self.assertEqual('true',field.get('focused'))
        self.assertEqual('true',field.get('password'))
        enter('Пинкод',pin);press('Открыть')
    def lock(self):
        d.tap(d.find(desc='Зашифровать часть'));d.wait_for(lambda: e.input_field().get('text')==PLACEHOLDER);p.hide_ime()
    def opened(self,clear=CLEAR):
        d.wait_for(lambda: e.input_field().get('text')==clear);p.hide_ime()
    def saved_cipher(self,index=0): return re.findall(r'`secret ([A-Za-z0-9+/=]+)`',self.note())[index]

    def test_encryption_pin_settings_cache_and_no_file_writes_while_editing(self):
        original,stamp=self.note(),self.stamp()
        self.open(PLACEHOLDER)
        self.assertEqual(PLACEHOLDER,e.input_field().get('text'))
        self.assertNotIn(CIPHER,d.adb('exec-out','cat','/data/local/tmp/obsnotes-navigation.xml').decode())
        d.tap(d.find(desc='Расшифровать часть'))
        d.wait_for(lambda: bool(d.nodes(d.tree(),text='Сначала создайте пароль в настройках')))
        self.assertEqual(original,self.note());self.exit();self.assertEqual(stamp,self.stamp())
        self.settings();press('Создать пароль')
        enter('Пароль',PASSWORD);enter('Повторите пароль','FixturePassword!2027');enter('Пинкод',PIN);enter('Повторите пинкод',PIN)
        for desc in ('Пароль','Повторите пароль','Пинкод','Повторите пинкод'):
            self.assertEqual('false',d.find(desc=desc).get('password'))
        press('Сохранить');self.assertIsNone(wrapped())
        enter('Повторите пароль',PASSWORD);enter('Повторите пинкод','0124');press('Сохранить');self.assertIsNone(wrapped())
        enter('Повторите пинкод',PIN);press('Сохранить')
        d.wait_for(lambda: wrapped() is not None)
        self.assertEqual(PASSWORD,decrypt_plugin(wrapped(),PIN));self.assertNotIn(PASSWORD,d.preferences().decode())
        self.back_settings();self.open(PLACEHOLDER)
        d.tap(d.find(desc='Расшифровать часть'));self.unlock('0000')
        d.wait_for(lambda: bool(d.nodes(d.tree(),text='Неверный пинкод')))
        self.assertEqual(PLACEHOLDER,e.input_field().get('text'))
        d.tap(d.find(desc='Расшифровать часть'));self.unlock();self.opened()
        self.assertEqual(original,self.note());self.assertEqual(stamp,self.stamp())
        self.assertIn('sealed',editor_envelope());self.assertNotIn(CLEAR,d.preferences().decode())
        self.lock();self.assertEqual(original,self.note());self.exit();self.assertEqual(stamp,self.stamp())
        print('Plugin ciphertext, absent password, wrong/correct PIN, encrypted password/recovery and read/relock no-op passed',flush=True)

        self.open('PublicOne');self.lock();self.assertEqual(original,self.note());self.exit()
        self.assertEqual('PublicOne',decrypt_plugin(self.saved_cipher(),PASSWORD))
        self.open(PLACEHOLDER);d.tap(d.find(desc='Расшифровать часть'));self.opened('PublicOne')
        # Process death clears the password cache, but restores encrypted recovery safely.
        before=self.note()
        d.adb('shell','am','force-stop','ru.obsnotes');d.adb('shell','am','start','-n','ru.obsnotes/.MainActivity')
        d.wait_for(lambda: bool(d.nodes(d.tree(),desc='Зашифровать часть')));p.hide_ime()
        self.assertEqual('PublicOne',e.input_field().get('text'));self.assertEqual(before,self.note())
        d.tap(d.find(desc='Зашифровать часть'));self.unlock()
        d.wait_for(lambda: e.input_field().get('text')==PLACEHOLDER);p.hide_ime()
        d.tap(d.find(desc='Расшифровать часть'));self.opened('PublicOne')
        field=editor_field();d.tap(field);d.adb('shell','input','keycombination','113','29');d.adb('shell','input','text','EditedSecret');p.hide_ime()
        self.assertEqual(before,self.note());self.exit()
        self.assertIn('EditedSecret',self.note());self.assertEqual(1,len(re.findall(r'`secret ',self.note())))
        print('Java → plugin, three-minute cache reuse, process-death cache reset and final open-lock plaintext save passed',flush=True)

        # PIN change always verifies the old PIN, even when the operation cache is valid.
        self.settings();press('Изменить пинкод');enter('Старый пинкод','0000');enter('Новый пинкод',NEWPIN);enter('Повторите новый пинкод',NEWPIN);press('Сохранить')
        d.wait_for(lambda: bool(d.nodes(d.tree(),text='Неверный старый пинкод')))
        self.assertEqual(PASSWORD,decrypt_plugin(wrapped(),PIN))
        press('Изменить пинкод');enter('Старый пинкод',PIN);enter('Новый пинкод',NEWPIN);enter('Повторите новый пинкод',NEWPIN);press('Сохранить')
        d.wait_for(lambda: decrypt_plugin(wrapped(),NEWPIN)==PASSWORD)
        self.assertIsNone(decrypt_plugin(wrapped(),PIN));self.back_settings()
        self.open('EditedSecret');d.tap(d.find(desc='Зашифровать часть'));self.unlock(NEWPIN)
        unlocked_at=time.monotonic();d.wait_for(lambda: e.input_field().get('text')==PLACEHOLDER);p.hide_ime()
        unsaved=self.note();self.assertIn('EditedSecret',unsaved)
        print('Old PIN required; new PIN preserves the AES password. Waiting for real 180-second cache expiry',flush=True)
        while time.monotonic()-unlocked_at<182:
            time.sleep(min(20,182-(time.monotonic()-unlocked_at)))
            d.adb('shell','input','keyevent','224')
            print('Cache expiry check: '+str(round(time.monotonic()-unlocked_at))+' seconds',flush=True)
        self.assertEqual(unsaved,self.note())
        d.tap(d.find(desc='Расшифровать часть'));self.unlock(NEWPIN);self.opened('EditedSecret')
        self.lock();self.exit()
        self.assertEqual('EditedSecret',decrypt_plugin(self.saved_cipher(),PASSWORD))
        print('Actual three-minute expiry requested PIN again; no background file writes during the wait',flush=True)

        self.settings();press('Изменить пароль');enter('Старый пинкод',NEWPIN);enter('Новый пароль','ChangedFixturePassword');enter('Повторите новый пароль','ChangedFixturePassword');press('Сохранить')
        d.wait_for(lambda: decrypt_plugin(wrapped(),NEWPIN)=='ChangedFixturePassword')
        self.assertNotIn('ChangedFixturePassword',d.preferences().decode())
        d.tap(d.find(desc='Удалить пароль'));enter('Старый пинкод','0000');press('Удалить')
        d.wait_for(lambda: bool(d.nodes(d.tree(),text='Неверный пинкод')));self.assertIsNotNone(wrapped())
        d.tap(d.find(desc='Удалить пароль'));enter('Старый пинкод',NEWPIN);press('Удалить')
        d.wait_for(lambda: wrapped() is None)
        self.assertIsNotNone(d.find(text='Создать пароль'))
        print('Password change and authenticated deletion passed',flush=True)

    def test_x_android_and_plugin_interoperate_with_unicode_passwords(self):
        password='Пароль🔑e\u0301'
        clear='Личная заметка\nВторая строка\n\nТретий абзац 🦉'
        cipher=_plugin('encryptToBase64',clear,password)
        wrap=_plugin('encryptToBase64',password,PIN)
        d.adb('shell','am','force-stop','ru.obsnotes')
        prefs=ET.fromstring(d.preferences())
        self.assertEqual(d.URI,next(n.text for n in prefs if n.get('name')=='vault'))
        ET.SubElement(prefs,'string',name=KEY).text=wrap
        d.write_preferences(ET.tostring(prefs,encoding='utf-8',xml_declaration=True))
        e.put(self.relative,SOURCE.replace(CIPHER,cipher))
        d.adb('shell','am','start','-n','ru.obsnotes/.MainActivity');self.main()
        before=self.note();self.open(PLACEHOLDER)
        d.tap(d.find(desc='Расшифровать часть'));self.unlock();self.opened(clear)
        self.assertEqual(before,self.note())
        d.tap(d.find(desc='Зашифровать часть'))
        d.wait_for(lambda:e.input_field().get('text')=='Личная заметка\nВторая строка\n\n'+PLACEHOLDER);p.hide_ime()
        self.exit()
        self.assertEqual('Третий абзац 🦉',decrypt_plugin(self.saved_cipher(),password))
        print('Real Android PBKDF2 UTF-8 password: plugin → Android and Android → plugin passed',flush=True)

    def test_y_decrypted_paragraphs_follow_the_cursor_like_ordinary_parts(self):
        before,stamp=self.note(),self.stamp()
        self.settings();press('Создать пароль')
        enter('Пароль',PASSWORD);enter('Повторите пароль',PASSWORD)
        enter('Пинкод',PIN);enter('Повторите пинкод',PIN);press('Сохранить')
        d.wait_for(lambda:wrapped() is not None);self.back_settings()
        self.open(PLACEHOLDER);d.tap(d.find(desc='Расшифровать часть'));self.unlock();self.opened(MULTI_CLEAR)
        self.assertIsNotNone(d.find(desc='Назначения части 1'))
        self.assertIsNotNone(d.find(desc='Назначения части 2'))
        field=editor_field();d.tap(field);d.adb('shell','input','keycombination','113','122')
        d.adb('shell','input','keyevent',*(['22']*len('FixtureSecret\nSecondLine')));p.hide_ime()
        d.tap(d.find(desc='Зашифровать часть'))
        d.wait_for(lambda:e.input_field().get('text')==PLACEHOLDER+'\n\nLastParagraph');p.hide_ime()
        self.assertEqual(before,self.note());self.assertEqual(stamp,self.stamp())
        field=editor_field();d.tap(field);d.adb('shell','input','keycombination','113','123');p.hide_ime()
        d.tap(d.find(desc='Зашифровать часть'))
        d.wait_for(lambda:e.input_field().get('text')==PLACEHOLDER+'\n\n'+PLACEHOLDER);p.hide_ime()
        self.exit()
        self.assertEqual('FixtureSecret\nSecondLine',decrypt_plugin(self.saved_cipher(0),PASSWORD))
        self.assertEqual('LastParagraph',decrypt_plugin(self.saved_cipher(1),PASSWORD))
        print('Decrypted blank lines split into parts: upper/lower cursor encryption saved two compatible independent secrets',flush=True)

    def test_z_cursor_selects_middle_part_and_lock_shares_the_section_row(self):
        before,stamp=self.note(),self.stamp()
        self.settings();press('Создать пароль')
        enter('Пароль',PASSWORD);enter('Повторите пароль','FixturePassword!2027')
        enter('Пинкод',PIN);enter('Повторите пинкод',PIN);press('Сохранить')
        self.assertIsNone(wrapped())
        self.assertGreater(e.pixels(d.find(desc='Повторите пароль'),(239,115,124)),20)
        self.assertFalse(any('не совпадают' in n.get('text','') for n in d.tree().iter('node')))
        enter('Повторите пароль',PASSWORD);press('Сохранить')
        d.wait_for(lambda: wrapped() is not None);self.back_settings()
        d.tap(d.find(desc='Новая заметка',down=False));d.wait_for(d.keyboard_shown)
        for i,text in enumerate(('AlphaPart','BetaPart','GammaPart')):
            if i:d.adb('shell','input','keyevent','66','66')
            d.adb('shell','input','text',text)
        d.wait_for(lambda: e.input_field().get('text')=='AlphaPart\n\nBetaPart\n\nGammaPart')
        d.adb('shell','input','keycombination','113','122')
        d.adb('shell','input','keyevent',*(['22']*len('AlphaPart\n\nBetaPart')))
        p.hide_ime()
        lock=d.find(desc='Зашифровать часть');sections=d.find(text='Разделы')
        self.assertLessEqual(abs(sum(d.bounds(lock)[1::2])-sum(d.bounds(sections)[1::2])),4)
        d.tap(lock);self.unlock()
        expected='AlphaPart\n\n'+PLACEHOLDER+'\n\nGammaPart'
        d.wait_for(lambda:e.input_field().get('text')==expected);p.hide_ime()
        self.assertEqual(before,self.note())
        d.tap(d.find(desc='Расшифровать часть'));self.opened('AlphaPart\n\nBetaPart\n\nGammaPart')
        field=editor_field();d.tap(field);d.adb('shell','input','keycombination','113','29');d.adb('shell','input','keyevent','67');p.hide_ime()
        self.exit();self.assertEqual(before,self.note());self.assertEqual(stamp,self.stamp())
        print('Middle part only, lock beside Sections, cached decrypt and empty-new-editor no-op passed',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--serial',required=True);parser.add_argument('--apk');parser.add_argument('--plugin',required=True)
    args=parser.parse_args();d.ADB=['/home/aborunov/Android/Sdk/platform-tools/adb','-s',args.serial];d.APK=args.apk;PLUGIN=args.plugin
    unittest.main(argv=['device_encryption'],verbosity=2)
