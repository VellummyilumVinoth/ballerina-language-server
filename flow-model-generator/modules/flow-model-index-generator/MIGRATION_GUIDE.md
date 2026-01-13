# Database Schema Migration Guide

## Overview

This guide covers how to migrate the `central-index.sqlite` database from the original schema (v1) to the enhanced schema (v2) that includes support for type definitions, records, enums, unions, classes, services, and clients.

## Migration Files

The following migration files have been created:

```
flow-model-generator/modules/flow-model-index-generator/src/main/resources/
├── central-index.sql                              # Original schema
├── central-index-enhanced.sql                     # Complete enhanced schema (v2)
└── migrations/
    ├── 001_add_type_definitions.sql              # Forward migration
    └── 001_add_type_definitions_rollback.sql     # Rollback migration
```

## What's New in Schema v2

### New Tables

1. **TypeDefinition** - Stores all type definitions (Records, Enums, Unions, Classes, Errors, Constants, typedesc)
2. **RecordField** - Stores fields for Record types
3. **EnumMember** - Stores members for Enum types
4. **UnionMember** - Stores member types for Union types
5. **TypeLink** - Stores relationships and references between types
6. **ClientDefinition** - Stores client definitions
7. **ServiceDefinition** - Stores service definitions with instructions
8. **ClassMethod** - Stores methods for Class types
9. **ClassMethodParameter** - Stores parameters for class methods
10. **schema_migrations** - Tracks applied migrations

### Modified Tables

1. **Package** - Added `description` column
2. **Function** - Added `client_id` column to associate functions with clients

## Migration Strategies

### Option 1: Incremental Migration (Recommended)

This approach migrates the existing database by adding new tables and columns.

#### Prerequisites

- Java 11+
- Existing `central-index.sqlite` database
- Backup of existing database

#### Steps

1. **Create a backup** (CRITICAL!)

```bash
cd flow-model-generator/modules/flow-model-generator-ls-extension/src/main/resources
cp central-index.sqlite central-index.backup-$(date +%Y%m%d).sqlite
```

2. **Run the migration using MigrationManager**

```bash
cd /Users/vinoth/Downloads/ballerina-language-server
./gradlew :flow-model-generator:modules:flow-model-index-generator:run \
  --args="io.ballerina.indexgenerator.MigrationManager"
```

Or using Java directly:

```bash
cd flow-model-generator/modules/flow-model-index-generator
javac -cp ".:lib/*" src/main/java/io/ballerina/indexgenerator/MigrationManager.java
java -cp ".:lib/*:src/main/java" io.ballerina.indexgenerator.MigrationManager
```

3. **Verify migration**

```bash
sqlite3 central-index.sqlite "SELECT * FROM schema_migrations;"
```

Expected output:
```
1|001_add_type_definitions|2026-01-06 ...|Add type definition support...
```

4. **Check new tables**

```bash
sqlite3 central-index.sqlite ".tables"
```

Should show all new tables: TypeDefinition, RecordField, EnumMember, etc.

### Option 2: Fresh Database Creation

This approach creates a completely new database from scratch using the enhanced schema.

#### Steps

1. **Rename or backup existing database**

```bash
cd flow-model-generator/modules/flow-model-generator-ls-extension/src/main/resources
mv central-index.sqlite central-index-v1-backup.sqlite
```

2. **Create new database with enhanced schema**

```bash
sqlite3 central-index.sqlite < ../../../flow-model-index-generator/src/main/resources/central-index-enhanced.sql
```

3. **Run IndexGenerator to populate data**

```bash
cd /Users/vinoth/Downloads/ballerina-language-server
./gradlew :flow-model-generator:modules:flow-model-index-generator:run \
  --args="io.ballerina.indexgenerator.IndexGenerator"
```

## Rolling Back Migrations

If something goes wrong, you can roll back the migration:

### Using MigrationManager

```bash
java -cp ".:lib/*:src/main/java" io.ballerina.indexgenerator.MigrationManager rollback
```

### Manual Rollback

```bash
cd flow-model-generator/modules/flow-model-generator-ls-extension/src/main/resources
sqlite3 central-index.sqlite < ../../../flow-model-index-generator/src/main/resources/migrations/001_add_type_definitions_rollback.sql
```

### Restore from Backup

```bash
cd flow-model-generator/modules/flow-model-generator-ls-extension/src/main/resources
rm central-index.sqlite
cp central-index.backup-YYYYMMDD.sqlite central-index.sqlite
```

## Populating New Tables

After migration, you need to populate the new tables with data. The `IndexGenerator` needs to be updated to extract and insert type definitions.

### Required Updates to IndexGenerator

The `IndexGenerator` class needs the following enhancements:

1. **Extract Type Definitions** from Ballerina packages
2. **Extract Record Fields** for each record type
3. **Extract Enum Members** for each enum type
4. **Extract Union Members** for each union type
5. **Extract Class Methods** for each class type
6. **Extract Client Definitions** from packages
7. **Extract Service Definitions** from packages
8. **Store Type Links** for cross-references

### Example: Adding Type Definition Extraction

```java
// In IndexGenerator class, after processing functions:

private void processTypeDefinitions(Module module, int packageId) {
    // Get all type definitions from module
    for (TypeDefinition typeDef : module.typeDefinitions()) {
        Symbol symbol = typeDef.symbol();
        if (symbol instanceof TypeSymbol typeSymbol) {
            String typeCategory = getTypeCategory(typeSymbol);
            String description = getDescription(symbol);

            int typeId = DatabaseManagerExtensions.insertTypeDefinition(
                packageId,
                typeSymbol.getName().orElse(""),
                description,
                typeCategory,
                null, // value (for constants)
                null, // baseType (for typedesc)
                null  // metadata
            );

            // Process based on type category
            switch (typeCategory) {
                case "Record":
                    processRecordFields(typeSymbol, typeId);
                    break;
                case "Enum":
                    processEnumMembers(typeSymbol, typeId);
                    break;
                case "Union":
                    processUnionMembers(typeSymbol, typeId);
                    break;
                case "Class":
                    processClassMethods(typeSymbol, typeId);
                    break;
            }
        }
    }
}

private void processRecordFields(TypeSymbol recordType, int typeId) {
    if (recordType.typeDescriptor() instanceof RecordTypeSymbol recordTypeSymbol) {
        Map<String, RecordFieldSymbol> fields = recordTypeSymbol.fieldDescriptors();
        int ordinal = 0;

        for (Map.Entry<String, RecordFieldSymbol> entry : fields.entrySet()) {
            String fieldName = entry.getKey();
            RecordFieldSymbol fieldSymbol = entry.getValue();

            String fieldType = getTypeAsJson(fieldSymbol.typeDescriptor());
            String description = getDescription(fieldSymbol);
            String defaultValue = getDefaultValue(fieldSymbol);
            int optional = fieldSymbol.isOptional() ? 1 : 0;

            DatabaseManagerExtensions.insertRecordField(
                typeId,
                fieldName,
                description,
                fieldType,
                defaultValue,
                optional,
                ordinal++
            );
        }
    }
}
```

## Database Schema Comparison

### Before Migration (v1)

```
Package (124 entries)
Function (3,212 entries)
Parameter (10,735 entries)
ParameterMemberType (9,334 entries)
FunctionConnector (junction table)
```

### After Migration (v2)

```
Package (124 entries) + description column
Function (3,212 entries) + client_id column
Parameter (10,735 entries)
ParameterMemberType (9,334 entries)
FunctionConnector (junction table)

+ TypeDefinition (NEW)
+ RecordField (NEW)
+ EnumMember (NEW)
+ UnionMember (NEW)
+ TypeLink (NEW)
+ ClientDefinition (NEW)
+ ServiceDefinition (NEW)
+ ClassMethod (NEW)
+ ClassMethodParameter (NEW)
+ schema_migrations (NEW)
```

## Verifying Migration Success

### Check Schema Version

```sql
SELECT * FROM schema_migrations;
```

### Verify Table Structure

```sql
.schema TypeDefinition
.schema RecordField
.schema EnumMember
```

### Check Existing Data Integrity

```sql
-- Verify packages still exist
SELECT COUNT(*) FROM Package;

-- Verify functions still exist
SELECT COUNT(*) FROM Function;

-- Verify parameters still exist
SELECT COUNT(*) FROM Parameter;

-- Check for any functions with client associations
SELECT f.name, c.name as client_name
FROM Function f
LEFT JOIN ClientDefinition c ON f.client_id = c.client_id
LIMIT 10;
```

## Performance Considerations

### Indexes

The migration automatically creates indexes on:
- TypeDefinition: name, package_id, type_category
- RecordField: type_id, name
- EnumMember: type_id
- UnionMember: type_id
- TypeLink: source_context/source_id, target_type_name
- ClientDefinition: package_id, name
- ServiceDefinition: package_id, service_type
- ClassMethod: class_type_id, name
- ClassMethodParameter: method_id

### Database Size

Expected database size increase:
- **Before**: ~8.8 MB
- **After (with full type data)**: ~15-20 MB (estimated)

The actual size depends on the number of type definitions in indexed packages.

## Troubleshooting

### Migration Fails with "table already exists"

The migration has been partially applied. Options:
1. Restore from backup and retry
2. Manually drop conflicting tables and retry
3. Check `schema_migrations` table to see what was applied

### Foreign Key Constraint Errors

Ensure foreign key constraints are enabled:

```sql
PRAGMA foreign_keys = ON;
```

### Migration Appears Successful but Tables Are Empty

The migration creates table structure but doesn't populate data. You need to:
1. Update `IndexGenerator` to extract type definitions
2. Re-run the index generation process

### Rollback Fails

If rollback fails:
1. Restore from backup (safest option)
2. Manually drop tables using rollback script
3. Check error messages for specific issues

## Next Steps

After successful migration:

1. **Update IndexGenerator** to populate new tables
2. **Update DatabaseManager** (query methods) to retrieve type definitions
3. **Test querying** new tables
4. **Update dependent code** that uses the database
5. **Update documentation** for new API methods

## Support

For issues or questions:
- Check the migration logs
- Review SQL error messages
- Verify backup exists before making changes
- Test on a copy of the database first

## References

- [SQLite ALTER TABLE](https://www.sqlite.org/lang_altertable.html)
- [SQLite Foreign Keys](https://www.sqlite.org/foreignkeys.html)
- [Ballerina Semantic API](https://ballerina.io/learn/by-example/#semantic-api)
