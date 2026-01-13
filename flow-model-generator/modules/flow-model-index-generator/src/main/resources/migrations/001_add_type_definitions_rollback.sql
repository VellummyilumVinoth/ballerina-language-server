-- Migration Rollback: Remove Type Definition Support
-- Version: 001
-- Description: Rolls back migration 001 - removes type definition tables and columns
-- Date: 2026-01-06
-- WARNING: This will delete all type definition data!

-- ============================================================================
-- ROLLBACK MIGRATION
-- ============================================================================

-- Drop indexes first
DROP INDEX IF EXISTS idx_classmethodparam_method;
DROP INDEX IF EXISTS idx_classmethod_name;
DROP INDEX IF EXISTS idx_classmethod_type;
DROP INDEX IF EXISTS idx_service_type;
DROP INDEX IF EXISTS idx_service_package;
DROP INDEX IF EXISTS idx_client_name;
DROP INDEX IF EXISTS idx_client_package;
DROP INDEX IF EXISTS idx_function_client;
DROP INDEX IF EXISTS idx_unionmember_type;
DROP INDEX IF EXISTS idx_enummember_type;
DROP INDEX IF EXISTS idx_typelink_target;
DROP INDEX IF EXISTS idx_typelink_source;
DROP INDEX IF EXISTS idx_recordfield_name;
DROP INDEX IF EXISTS idx_recordfield_type;
DROP INDEX IF EXISTS idx_typedef_category;
DROP INDEX IF EXISTS idx_typedef_package;
DROP INDEX IF EXISTS idx_typedef_name;

-- Drop tables in reverse dependency order
DROP TABLE IF EXISTS ClassMethodParameter;
DROP TABLE IF EXISTS ClassMethod;
DROP TABLE IF EXISTS ServiceDefinition;
DROP TABLE IF EXISTS ClientDefinition;
DROP TABLE IF EXISTS UnionMember;
DROP TABLE IF EXISTS EnumMember;
DROP TABLE IF EXISTS TypeLink;
DROP TABLE IF EXISTS RecordField;
DROP TABLE IF EXISTS TypeDefinition;

-- Remove the client_id column from Function table
-- Note: SQLite doesn't support DROP COLUMN directly before version 3.35.0
-- We need to recreate the table without the column

-- Step 1: Create temporary table with old schema
CREATE TABLE Function_backup (
    function_id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT CHECK(kind IN ('FUNCTION', 'CONNECTOR', 'REMOTE', 'RESOURCE')),
    name TEXT NOT NULL,
    description TEXT,
    package_id INTEGER,
    return_type JSON,
    resource_path TEXT NOT NULL,
    return_error INTEGER CHECK(return_error IN (0, 1)),
    inferred_return_type INTEGER CHECK(inferred_return_type IN (0, 1)),
    import_statements TEXT,
    FOREIGN KEY (package_id) REFERENCES Package(package_id) ON DELETE CASCADE
);

-- Step 2: Copy data (excluding client_id)
INSERT INTO Function_backup
SELECT function_id, kind, name, description, package_id, return_type,
       resource_path, return_error, inferred_return_type, import_statements
FROM Function;

-- Step 3: Drop original table
DROP TABLE Function;

-- Step 4: Rename backup to original
ALTER TABLE Function_backup RENAME TO Function;

-- Recreate FunctionConnector table (it was dropped due to foreign key)
CREATE TABLE IF NOT EXISTS FunctionConnector (
    function_id INTEGER,
    connector_id INTEGER,
    PRIMARY KEY (function_id, connector_id),
    FOREIGN KEY (function_id) REFERENCES Function(function_id) ON DELETE CASCADE,
    FOREIGN KEY (connector_id) REFERENCES Function(function_id) ON DELETE CASCADE
);

-- Remove description column from Package table
-- Again, need to recreate table for older SQLite versions
CREATE TABLE Package_backup (
    package_id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_name TEXT NOT NULL,
    module_name TEXT NOT NULL,
    org TEXT NOT NULL,
    version TEXT,
    keywords TEXT
);

-- Copy data (excluding description)
INSERT INTO Package_backup
SELECT package_id, package_name, module_name, org, version, keywords
FROM Package;

-- Drop original and rename
DROP TABLE Package;
ALTER TABLE Package_backup RENAME TO Package;

-- Remove migration record
DELETE FROM schema_migrations WHERE version = 1;

-- ============================================================================
-- End of Rollback
-- ============================================================================
