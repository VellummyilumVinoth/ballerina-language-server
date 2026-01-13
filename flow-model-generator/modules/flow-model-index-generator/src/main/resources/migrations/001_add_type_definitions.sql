-- Migration: Add Type Definition Support
-- Version: 001
-- Description: Adds support for storing type definitions (Records, Enums, Unions, Classes, etc.)
-- Date: 2026-01-06

-- ============================================================================
-- FORWARD MIGRATION
-- ============================================================================

-- Add description field to Package table
ALTER TABLE Package ADD COLUMN description TEXT;

-- ============================================================================
-- Create TypeDefinition table
-- ============================================================================
CREATE TABLE TypeDefinition (
    type_id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    description TEXT,
    type_category TEXT CHECK(type_category IN (
        'Record',
        'Enum',
        'Union',
        'Class',
        'Error',
        'Constant',
        'typedesc'
    )) NOT NULL,
    package_id INTEGER NOT NULL,
    value TEXT, -- For constants: the actual value
    base_type TEXT, -- For typedesc: the base type
    metadata JSON, -- Additional metadata as needed
    FOREIGN KEY (package_id) REFERENCES Package(package_id) ON DELETE CASCADE
);

-- Create index for faster type lookups
CREATE INDEX idx_typedef_name ON TypeDefinition(name);
CREATE INDEX idx_typedef_package ON TypeDefinition(package_id);
CREATE INDEX idx_typedef_category ON TypeDefinition(type_category);

-- ============================================================================
-- Create RecordField table
-- ============================================================================
CREATE TABLE RecordField (
    field_id INTEGER PRIMARY KEY AUTOINCREMENT,
    type_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    field_type JSON NOT NULL, -- Structured type information: {"name": "string", "optional": false}
    default_value TEXT,
    optional INTEGER CHECK(optional IN (0, 1)) DEFAULT 0,
    ordinal INTEGER NOT NULL, -- Field order/position
    FOREIGN KEY (type_id) REFERENCES TypeDefinition(type_id) ON DELETE CASCADE
);

-- Create index for faster field lookups
CREATE INDEX idx_recordfield_type ON RecordField(type_id);
CREATE INDEX idx_recordfield_name ON RecordField(name);

-- ============================================================================
-- Create TypeLink table for field type references
-- ============================================================================
CREATE TABLE TypeLink (
    link_id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_context TEXT CHECK(source_context IN (
        'record_field',
        'parameter',
        'return_type',
        'union_member',
        'class_method'
    )) NOT NULL,
    source_id INTEGER NOT NULL, -- ID of the source (field_id, parameter_id, function_id, etc.)
    target_type_name TEXT NOT NULL, -- Name of the referenced type
    category TEXT CHECK(category IN ('internal', 'external')) DEFAULT 'internal',
    record_name TEXT, -- For internal links, the actual record name
    package_org TEXT, -- For external links
    package_name TEXT, -- For external links
    package_version TEXT -- For external links
);

-- Create indexes for faster link lookups
CREATE INDEX idx_typelink_source ON TypeLink(source_context, source_id);
CREATE INDEX idx_typelink_target ON TypeLink(target_type_name);

-- ============================================================================
-- Create EnumMember table
-- ============================================================================
CREATE TABLE EnumMember (
    member_id INTEGER PRIMARY KEY AUTOINCREMENT,
    type_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    ordinal INTEGER NOT NULL, -- Position in enum
    FOREIGN KEY (type_id) REFERENCES TypeDefinition(type_id) ON DELETE CASCADE
);

-- Create index for faster enum member lookups
CREATE INDEX idx_enummember_type ON EnumMember(type_id);

-- ============================================================================
-- Create UnionMember table
-- ============================================================================
CREATE TABLE UnionMember (
    union_member_id INTEGER PRIMARY KEY AUTOINCREMENT,
    type_id INTEGER NOT NULL,
    member_type_name TEXT NOT NULL, -- Type name of the union member
    ordinal INTEGER NOT NULL, -- Position in union
    FOREIGN KEY (type_id) REFERENCES TypeDefinition(type_id) ON DELETE CASCADE
);

-- Create index for faster union member lookups
CREATE INDEX idx_unionmember_type ON UnionMember(type_id);

-- ============================================================================
-- Create ClientDefinition table
-- ============================================================================
CREATE TABLE ClientDefinition (
    client_id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    description TEXT,
    package_id INTEGER NOT NULL,
    metadata JSON, -- Additional client-specific metadata
    FOREIGN KEY (package_id) REFERENCES Package(package_id) ON DELETE CASCADE,
    UNIQUE(name, package_id)
);

-- Create index for faster client lookups
CREATE INDEX idx_client_package ON ClientDefinition(package_id);
CREATE INDEX idx_client_name ON ClientDefinition(name);

-- ============================================================================
-- Create ServiceDefinition table
-- ============================================================================
CREATE TABLE ServiceDefinition (
    service_id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_id INTEGER NOT NULL,
    service_type TEXT NOT NULL, -- 'generic', 'http', 'grpc', etc.
    instructions TEXT, -- Service writing instructions (markdown)
    test_instructions TEXT, -- Test generation instructions (markdown)
    listener_config JSON, -- Listener configuration: {"name": "Listener", "parameters": [...]}
    metadata JSON, -- Additional service-specific metadata
    FOREIGN KEY (package_id) REFERENCES Package(package_id) ON DELETE CASCADE
);

-- Create index for faster service lookups
CREATE INDEX idx_service_package ON ServiceDefinition(package_id);
CREATE INDEX idx_service_type ON ServiceDefinition(service_type);

-- ============================================================================
-- Enhance Function table with client relationship
-- ============================================================================
ALTER TABLE Function ADD COLUMN client_id INTEGER REFERENCES ClientDefinition(client_id) ON DELETE CASCADE;

-- Create index for client functions
CREATE INDEX idx_function_client ON Function(client_id);

-- ============================================================================
-- Create ClassMethod table for class-specific methods
-- ============================================================================
CREATE TABLE ClassMethod (
    method_id INTEGER PRIMARY KEY AUTOINCREMENT,
    class_type_id INTEGER NOT NULL, -- References TypeDefinition where type_category='Class'
    name TEXT NOT NULL,
    description TEXT,
    method_type TEXT CHECK(method_type IN (
        'Constructor',
        'Method',
        'Remote Function',
        'Resource Function'
    )) NOT NULL,
    return_type JSON, -- Structured return type information
    return_error INTEGER CHECK(return_error IN (0, 1)) DEFAULT 0,
    inferred_return_type INTEGER CHECK(inferred_return_type IN (0, 1)) DEFAULT 0,
    import_statements TEXT,
    FOREIGN KEY (class_type_id) REFERENCES TypeDefinition(type_id) ON DELETE CASCADE
);

-- Create index for faster class method lookups
CREATE INDEX idx_classmethod_type ON ClassMethod(class_type_id);
CREATE INDEX idx_classmethod_name ON ClassMethod(name);

-- ============================================================================
-- Create ClassMethodParameter table
-- ============================================================================
CREATE TABLE ClassMethodParameter (
    parameter_id INTEGER PRIMARY KEY AUTOINCREMENT,
    method_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    label TEXT,
    kind TEXT CHECK(kind IN (
        'REQUIRED',
        'DEFAULTABLE',
        'INCLUDED_RECORD',
        'REST_PARAMETER',
        'INCLUDED_FIELD',
        'INCLUDED_RECORD_REST',
        'PARAM_FOR_TYPE_INFER',
        'PATH_PARAM',
        'PATH_REST_PARAM'
    )) NOT NULL,
    type JSON NOT NULL, -- JSON type for parameter type information
    placeholder TEXT,
    default_value TEXT,
    optional INTEGER CHECK(optional IN (0, 1)) DEFAULT 0,
    import_statements TEXT,
    FOREIGN KEY (method_id) REFERENCES ClassMethod(method_id) ON DELETE CASCADE
);

-- Create index for faster parameter lookups
CREATE INDEX idx_classmethodparam_method ON ClassMethodParameter(method_id);

-- ============================================================================
-- Create metadata table for tracking migrations
-- ============================================================================
CREATE TABLE IF NOT EXISTS schema_migrations (
    version INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    applied_at TEXT DEFAULT CURRENT_TIMESTAMP,
    description TEXT
);

-- Record this migration
INSERT INTO schema_migrations (version, name, description)
VALUES (1, '001_add_type_definitions', 'Add type definition support including Records, Enums, Unions, Classes, Services, and Clients');

-- ============================================================================
-- End of Migration
-- ============================================================================
