# Database Schema Migration Summary

## Quick Reference

### Migration Files Created

| File | Purpose |
|------|---------|
| `migrations/001_add_type_definitions.sql` | Forward migration to add type definition support |
| `migrations/001_add_type_definitions_rollback.sql` | Rollback migration to remove changes |
| `central-index-enhanced.sql` | Complete enhanced schema for fresh installations |
| `MigrationManager.java` | Java utility to apply migrations |
| `DatabaseManagerExtensions.java` | New methods for type definition tables |

### Schema Changes Summary

#### New Tables (10)

1. **TypeDefinition** - Core type metadata
   - Columns: type_id, name, description, type_category, package_id, value, base_type, metadata
   - Categories: Record, Enum, Union, Class, Error, Constant, typedesc

2. **RecordField** - Record field definitions
   - Columns: field_id, type_id, name, description, field_type (JSON), default_value, optional, ordinal

3. **EnumMember** - Enum member values
   - Columns: member_id, type_id, name, description, ordinal

4. **UnionMember** - Union type members
   - Columns: union_member_id, type_id, member_type_name, ordinal

5. **TypeLink** - Type cross-references
   - Columns: link_id, source_context, source_id, target_type_name, category, record_name, package_org, package_name, package_version

6. **ClientDefinition** - Client metadata
   - Columns: client_id, name, description, package_id, metadata (JSON)

7. **ServiceDefinition** - Service templates
   - Columns: service_id, package_id, service_type, instructions, test_instructions, listener_config (JSON), metadata (JSON)

8. **ClassMethod** - Class method definitions
   - Columns: method_id, class_type_id, name, description, method_type, return_type (JSON), return_error, inferred_return_type, import_statements

9. **ClassMethodParameter** - Class method parameters
   - Columns: parameter_id, method_id, name, description, label, kind, type (JSON), placeholder, default_value, optional, import_statements

10. **schema_migrations** - Migration tracking
    - Columns: version, name, applied_at, description

#### Modified Tables (2)

1. **Package** - Added `description` column
2. **Function** - Added `client_id` column (foreign key to ClientDefinition)

### New Indexes (18)

- `idx_typedef_name`, `idx_typedef_package`, `idx_typedef_category`
- `idx_recordfield_type`, `idx_recordfield_name`
- `idx_enummember_type`
- `idx_unionmember_type`
- `idx_typelink_source`, `idx_typelink_target`
- `idx_client_package`, `idx_client_name`
- `idx_service_package`, `idx_service_type`
- `idx_function_client`
- `idx_classmethod_type`, `idx_classmethod_name`
- `idx_classmethodparam_method`

## Data Gap Resolution

### Before Migration

| Data Type | Available in SQLite | Available in context.json |
|-----------|---------------------|---------------------------|
| Type Definitions | ❌ | ✅ |
| Record Fields | ❌ | ✅ |
| Enum Members | ❌ | ✅ |
| Union Members | ❌ | ✅ |
| Class Methods | ❌ | ✅ |
| Service Instructions | ❌ | ✅ |
| Client Metadata | ⚠️ Partial | ✅ |

### After Migration

| Data Type | Available in SQLite | Notes |
|-----------|---------------------|-------|
| Type Definitions | ✅ | New TypeDefinition table |
| Record Fields | ✅ | New RecordField table |
| Enum Members | ✅ | New EnumMember table |
| Union Members | ✅ | New UnionMember table |
| Class Methods | ✅ | New ClassMethod + ClassMethodParameter tables |
| Service Instructions | ✅ | New ServiceDefinition table |
| Client Metadata | ✅ | New ClientDefinition table + Function.client_id |

## Quick Start

### 1. Apply Migration (Incremental)

```bash
# Backup first!
cd flow-model-generator/modules/flow-model-generator-ls-extension/src/main/resources
cp central-index.sqlite central-index-backup.sqlite

# Apply migration
cd ../../flow-model-index-generator
java -cp ".:lib/*:src/main/java" io.ballerina.indexgenerator.MigrationManager
```

### 2. Fresh Installation

```bash
# Create new database
cd flow-model-generator/modules/flow-model-generator-ls-extension/src/main/resources
sqlite3 central-index.sqlite < ../../../flow-model-index-generator/src/main/resources/central-index-enhanced.sql
```

### 3. Verify Migration

```bash
sqlite3 central-index.sqlite "SELECT * FROM schema_migrations;"
sqlite3 central-index.sqlite ".tables"
```

## Using New Database Methods

### Insert Type Definition

```java
import io.ballerina.indexgenerator.DatabaseManagerExtensions;

// Insert a Record type
int typeId = DatabaseManagerExtensions.insertTypeDefinition(
    packageId,
    "ClientConfiguration",
    "Configuration for HTTP client",
    "Record",
    null,  // value (for constants)
    null,  // baseType (for typedesc)
    null   // metadata
);

// Insert record fields
DatabaseManagerExtensions.insertRecordField(
    typeId,
    "timeout",
    "Request timeout in seconds",
    "{\"name\": \"int\", \"optional\": false}",
    "30",   // default value
    0,      // not optional
    0       // ordinal position
);
```

### Insert Enum Type

```java
int enumTypeId = DatabaseManagerExtensions.insertTypeDefinition(
    packageId,
    "HttpVersion",
    "HTTP protocol versions",
    "Enum",
    null,
    null,
    null
);

// Insert enum members
DatabaseManagerExtensions.insertEnumMember(enumTypeId, "HTTP_1_0", "HTTP/1.0 protocol", 0);
DatabaseManagerExtensions.insertEnumMember(enumTypeId, "HTTP_1_1", "HTTP/1.1 protocol", 1);
DatabaseManagerExtensions.insertEnumMember(enumTypeId, "HTTP_2_0", "HTTP/2.0 protocol", 2);
```

### Insert Union Type

```java
int unionTypeId = DatabaseManagerExtensions.insertTypeDefinition(
    packageId,
    "ClientAuthConfig",
    "Authentication configuration options",
    "Union",
    null,
    null,
    null
);

// Insert union members
DatabaseManagerExtensions.insertUnionMember(unionTypeId, "CredentialsConfig", 0);
DatabaseManagerExtensions.insertUnionMember(unionTypeId, "BearerTokenConfig", 1);
DatabaseManagerExtensions.insertUnionMember(unionTypeId, "JwtIssuerConfig", 2);
```

### Insert Client Definition

```java
int clientId = DatabaseManagerExtensions.insertClientDefinition(
    packageId,
    "Client",
    "HTTP client for making requests",
    null  // metadata
);

// Associate functions with client
DatabaseManagerExtensions.updateFunctionClientId(getFunctionId, clientId);
DatabaseManagerExtensions.updateFunctionClientId(postFunctionId, clientId);
```

### Insert Service Definition

```java
String instructions = "# Service Instructions\\n\\n- Always declare listener...";
String testInstructions = "# Test Instructions\\n\\n- Generate test for each resource...";
String listenerConfig = "{\"name\": \"Listener\", \"parameters\": [...]}";

DatabaseManagerExtensions.insertServiceDefinition(
    packageId,
    "generic",
    instructions,
    testInstructions,
    listenerConfig,
    null  // metadata
);
```

## Data Population Strategy

### Phase 1: Migrate Existing Database
✅ Add new tables and columns
✅ Keep existing data intact

### Phase 2: Populate Type Definitions
Parse context.json or Ballerina packages to extract:
- Type definitions (Records, Enums, Unions, Classes)
- Record fields
- Enum members
- Union members
- Class methods

### Phase 3: Populate Service & Client Metadata
Extract from context.json:
- Service instructions
- Test generation instructions
- Client definitions
- Associate functions with clients

### Phase 4: Create Type Links
Establish relationships:
- Record field → Type references
- Parameter → Type references
- Return type → Type references
- Union members → Type references

## Expected Database Size

| Schema Version | Database Size | Record Count Estimate |
|----------------|---------------|----------------------|
| v1 (Original) | 8.8 MB | ~17K total records |
| v2 (Enhanced, empty) | 9.0 MB | ~17K + empty tables |
| v2 (Enhanced, populated) | 15-20 MB | ~50K-70K records |

## Compatibility

### Backward Compatibility
✅ All existing queries continue to work
✅ No breaking changes to existing tables
✅ Only additive changes

### Forward Compatibility
⚠️ Code using new tables requires updated DatabaseManager
⚠️ Query methods need to be added for new tables
⚠️ IndexGenerator needs updates to populate new tables

## Rollback Plan

If issues occur:

1. **Immediate**: Use backup
   ```bash
   cp central-index-backup.sqlite central-index.sqlite
   ```

2. **Controlled**: Use rollback script
   ```bash
   sqlite3 central-index.sqlite < migrations/001_add_type_definitions_rollback.sql
   ```

3. **Nuclear**: Recreate from original schema
   ```bash
   rm central-index.sqlite
   sqlite3 central-index.sqlite < central-index.sql
   # Re-run IndexGenerator
   ```

## Testing Checklist

- [ ] Backup created successfully
- [ ] Migration applies without errors
- [ ] All new tables exist
- [ ] Existing data intact (Package, Function, Parameter counts match)
- [ ] Indexes created successfully
- [ ] Foreign key constraints work
- [ ] Can insert into new tables
- [ ] Can query new tables
- [ ] Can rollback successfully
- [ ] Performance acceptable (query time < 100ms)

## Next Implementation Steps

1. ✅ Create migration scripts
2. ✅ Create MigrationManager utility
3. ✅ Create DatabaseManagerExtensions
4. ⏳ Update IndexGenerator to extract types
5. ⏳ Create context.json parser
6. ⏳ Add query methods to DatabaseManager
7. ⏳ Update tests
8. ⏳ Update documentation

## Support & Troubleshooting

### Common Issues

**Issue**: Migration fails with "cannot add column"
**Solution**: SQLite version < 3.35.0. Use table recreation approach or upgrade SQLite.

**Issue**: Foreign key constraint error
**Solution**: Enable foreign keys: `PRAGMA foreign_keys = ON;`

**Issue**: Tables exist but are empty
**Solution**: Normal - populate using IndexGenerator updates or context.json parser.

### Getting Help

- Review `MIGRATION_GUIDE.md` for detailed instructions
- Check migration logs for specific errors
- Verify SQLite version: `sqlite3 --version`
- Test on database copy first
