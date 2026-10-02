/* One line per schema element, sorted, so two databases can be diffed as text.
   Run with:  sqlcmd -d <database> -h -1 -W -i schema-fingerprint.sql

   Every catalog column is coerced to the database's default collation first: the catalog's own
   collation differs from it, and mixing the two in one expression is an error.

   Names SQL Server generated itself (PK__x__3213E83F...) differ in every database, so such
   constraints are identified by what they ARE - table, columns, definition - not by name. */
SET NOCOUNT ON;

/* columns: type, size, nullability, identity, collation */
SELECT 'COLUMN ' + (t.name COLLATE DATABASE_DEFAULT) + '.' + (c.name COLLATE DATABASE_DEFAULT) + ' ' + (ty.name COLLATE DATABASE_DEFAULT)
     + '(' + CAST(c.max_length AS varchar(10)) + ',' + CAST(c.precision AS varchar(10)) + ',' + CAST(c.scale AS varchar(10)) + ')'
     + CASE WHEN c.is_nullable = 1 THEN ' NULL' ELSE ' NOT NULL' END
     + CASE WHEN c.is_identity = 1 THEN ' IDENTITY(' + CAST(IDENT_SEED((t.name COLLATE DATABASE_DEFAULT)) AS varchar(20)) + ',' + CAST(IDENT_INCR((t.name COLLATE DATABASE_DEFAULT)) AS varchar(20)) + ')' ELSE '' END
     + ISNULL(' COLLATE ' + (c.collation_name COLLATE DATABASE_DEFAULT), '')
FROM sys.columns c
JOIN sys.tables t ON t.object_id = c.object_id
JOIN sys.types ty ON ty.user_type_id = c.user_type_id
UNION ALL
/* defaults, by column */
SELECT 'DEFAULT ' + (t.name COLLATE DATABASE_DEFAULT) + '.' + (c.name COLLATE DATABASE_DEFAULT) + ' ' + (dc.definition COLLATE DATABASE_DEFAULT)
FROM sys.default_constraints dc
JOIN sys.tables t ON t.object_id = dc.parent_object_id
JOIN sys.columns c ON c.object_id = dc.parent_object_id AND c.column_id = dc.parent_column_id
UNION ALL
/* check constraints */
SELECT 'CHECK ' + (t.name COLLATE DATABASE_DEFAULT) + ' ' + CASE WHEN cc.is_system_named = 1 THEN '' ELSE (cc.name COLLATE DATABASE_DEFAULT) END + ' ' + (cc.definition COLLATE DATABASE_DEFAULT)
FROM sys.check_constraints cc
JOIN sys.tables t ON t.object_id = cc.parent_object_id
UNION ALL
/* primary keys, unique constraints and indexes: keys, includes, uniqueness, filter */
SELECT 'INDEX ' + (t.name COLLATE DATABASE_DEFAULT) + ' '
     + CASE WHEN i.is_primary_key = 1 THEN 'PK' WHEN i.is_unique_constraint = 1 THEN 'UQ' ELSE 'IX' END + ' '
     + CASE WHEN k.is_system_named = 1 THEN '' ELSE (i.name COLLATE DATABASE_DEFAULT) END
     + ' ' + (i.type_desc COLLATE DATABASE_DEFAULT) + CASE WHEN i.is_unique = 1 THEN ' UNIQUE' ELSE '' END
     + ' (' + ISNULL(STUFF((SELECT ',' + (c.name COLLATE DATABASE_DEFAULT) + CASE WHEN ic.is_descending_key = 1 THEN ' DESC' ELSE '' END
                            FROM sys.index_columns ic JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                            WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.is_included_column = 0
                            ORDER BY ic.key_ordinal FOR XML PATH('')), 1, 1, ''), '') + ')'
     + ISNULL(' INCLUDE (' + STUFF((SELECT ',' + (c.name COLLATE DATABASE_DEFAULT)
                            FROM sys.index_columns ic JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                            WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.is_included_column = 1
                            ORDER BY (c.name COLLATE DATABASE_DEFAULT) FOR XML PATH('')), 1, 1, '') + ')', '')
     + ISNULL(' WHERE ' + (i.filter_definition COLLATE DATABASE_DEFAULT), '')
FROM sys.indexes i
JOIN sys.tables t ON t.object_id = i.object_id
LEFT JOIN sys.key_constraints k ON k.parent_object_id = i.object_id AND k.unique_index_id = i.index_id
WHERE i.type > 0
UNION ALL
/* foreign keys: columns on both sides and the referential actions */
SELECT 'FK ' + (pt.name COLLATE DATABASE_DEFAULT) + '(' + STUFF((SELECT ',' + (pc.name COLLATE DATABASE_DEFAULT) FROM sys.foreign_key_columns fkc
                                      JOIN sys.columns pc ON pc.object_id = fkc.parent_object_id AND pc.column_id = fkc.parent_column_id
                                      WHERE fkc.constraint_object_id = fk.object_id ORDER BY fkc.constraint_column_id FOR XML PATH('')), 1, 1, '')
     + ') -> ' + (rt.name COLLATE DATABASE_DEFAULT) + '(' + STUFF((SELECT ',' + (rc.name COLLATE DATABASE_DEFAULT) FROM sys.foreign_key_columns fkc
                                      JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id
                                      WHERE fkc.constraint_object_id = fk.object_id ORDER BY fkc.constraint_column_id FOR XML PATH('')), 1, 1, '')
     + ') ' + CASE WHEN fk.is_system_named = 1 THEN '' ELSE (fk.name COLLATE DATABASE_DEFAULT) END
     + ' DEL=' + (fk.delete_referential_action_desc COLLATE DATABASE_DEFAULT) + ' UPD=' + (fk.update_referential_action_desc COLLATE DATABASE_DEFAULT)
FROM sys.foreign_keys fk
JOIN sys.tables pt ON pt.object_id = fk.parent_object_id
JOIN sys.tables rt ON rt.object_id = fk.referenced_object_id
UNION ALL
/* views: name and a whitespace-insensitive hash of the definition */
SELECT 'VIEW ' + (v.name COLLATE DATABASE_DEFAULT) + ' ' + CONVERT(varchar(64), HASHBYTES('SHA2_256',
       REPLACE(REPLACE(REPLACE(REPLACE(m.definition, CHAR(13), ''), CHAR(10), ''), CHAR(9), ''), ' ', '')), 2)
FROM sys.views v
JOIN sys.sql_modules m ON m.object_id = v.object_id
WHERE v.is_ms_shipped = 0
UNION ALL
SELECT 'DATABASE COLLATION ' + CAST(DATABASEPROPERTYEX(DB_NAME(), 'Collation') AS varchar(100))
ORDER BY 1;


