"""Exercise the production migration DDL against an isolated SQLite v5 fixture."""
import json
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[5]

class ScanDirectoryMigrationTest(unittest.TestCase):
    def test_migration_preserves_existing_tables_and_records(self):
        schema = json.loads((ROOT / 'module/data/schemas/com.rawsmusic.module.data.db.MusicDatabase/5.json').read_text(encoding='utf-8'))['database']
        connection = sqlite3.connect(':memory:')
        for table in schema['entities']:
            connection.execute(table['createSql'].replace('${TABLE_NAME}', table['tableName']))
            for index in table.get('indices', []):
                connection.execute(index['createSql'].replace('${TABLE_NAME}', table['tableName']))
            fields = table['fields']
            columns = [field['columnName'] for field in fields]
            values = [1 if field['affinity'] == 'INTEGER' else 0.25 if field['affinity'] == 'REAL' else 'fixture' for field in fields]
            connection.execute('INSERT INTO "' + table['tableName'] + '" (' + ','.join('"' + c + '"' for c in columns) + ') VALUES (' + ','.join('?' for _ in columns) + ')', values)
            if table['tableName'] == 'folder_files':
                second = values.copy()
                for column, value in [('_id', 2), ('cue_track_index', 2), ('cue_offset_ms', 2000)]:
                    second[columns.index(column)] = value
                connection.execute('INSERT INTO folder_files (' + ','.join('"' + c + '"' for c in columns) + ') VALUES (' + ','.join('?' for _ in columns) + ')', second)
        before = {table['tableName']: connection.execute('SELECT * FROM "' + table['tableName'] + '"').fetchall() for table in schema['entities']}
        source = (ROOT / 'module/data/src/main/kotlin/com/rawsmusic/module/data/db/ScanDirectorySchema.kt').read_text()
        sql = re.search(r'const val CREATE_TABLE = "(.*?)"', source).group(1)
        connection.execute(sql)
        self.assertEqual(['source_key', 'modified_at', 'checked_at'], [row[1] for row in connection.execute('PRAGMA table_info(scan_directories)')])
        generated = json.loads((ROOT / 'module/data/schemas/com.rawsmusic.module.data.db.MusicDatabase/6.json').read_text(encoding='utf-8'))['database']
        reference = sqlite3.connect(':memory:')
        new_table = next(table for table in generated['entities'] if table['tableName'] == 'scan_directories')
        reference.execute(new_table['createSql'].replace('${TABLE_NAME}', 'scan_directories'))
        self.assertEqual(reference.execute('PRAGMA table_info(scan_directories)').fetchall(), connection.execute('PRAGMA table_info(scan_directories)').fetchall())
        reference.close()
        for name, rows in before.items():
            self.assertEqual(rows, connection.execute('SELECT * FROM "' + name + '"').fetchall())
        connection.close()

if __name__ == '__main__':
    unittest.main()
