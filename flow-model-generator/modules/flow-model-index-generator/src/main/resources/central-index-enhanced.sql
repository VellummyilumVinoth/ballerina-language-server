-- Enhanced Central Index Database Schema
-- Version: 2.0
-- Description: Complete schema including type definitions, services, and clients
-- Date: 2026-01-06

-- ============================================================================
-- Drop existing tables if they exist
-- ============================================================================
DROP TABLE IF EXISTS schema_migrations;
DROP TABLE IF EXISTS ClassMethodParameter;
DROP TABLE IF EXISTS ClassMethod;
DROP TABLE IF EXISTS ServiceDefinition;
DROP TABLE IF EXISTS TypeLink;
DROP TABLE IF EXISTS UnionMember;
DROP TABLE IF EXISTS EnumMember;
DROP TABLE IF EXISTS RecordField;
DROP TABLE IF EXISTS TypeDefinition;
DROP TABLE IF EXISTS ClientDefinition;
DROP TABLE IF EXISTS FunctionConnector;
DROP TABLE IF EXISTS ParameterMemberType;
DROP TABLE IF EXISTS Parameter;
DROP TABLE IF EXISTS Function;
DROP TABLE IF EXISTS Package;

-- ============================================================================
-- Package Table
-- ============================================================================
CREATE TABLE Package (
    package_id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_name TEXT NOT NULL,
    module_name TEXT NOT NULL,
    org TEXT NOT NULL,
    version TEXT,
    keywords TEXT,
    description TEXT
);

-- ============================================================================
-- TypeDefinition Table
-- Stores all type definitions: Records, Enums, Unions, Classes, Errors, etc.
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

CREATE INDEX idx_typedef_name ON TypeDefinition(name);
CREATE INDEX idx_typedef_package ON TypeDefinition(package_id);
CREATE INDEX idx_typedef_category ON TypeDefinition(type_category);

-- ============================================================================
-- RecordField Table
-- Stores fields for Record types
-- ============================================================================
CREATE TABLE RecordField (
    field_id INTEGER PRIMARY KEY AUTOINCREMENT,
    type_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    field_type JSON NOT NULL, -- Structured type: {"name": "string", "optional": false}
    default_value TEXT,
    optional INTEGER CHECK(optional IN (0, 1)) DEFAULT 0,
    ordinal INTEGER NOT NULL, -- Field order
    FOREIGN KEY (type_id) REFERENCES TypeDefinition(type_id) ON DELETE CASCADE
);

CREATE INDEX idx_recordfield_type ON RecordField(type_id);
CREATE INDEX idx_recordfield_name ON RecordField(name);

-- ============================================================================
-- EnumMember Table
-- Stores members for Enum types
-- ============================================================================
CREATE TABLE EnumMember (
    member_id INTEGER PRIMARY KEY AUTOINCREMENT,
    type_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    ordinal INTEGER NOT NULL, -- Position in enum
    FOREIGN KEY (type_id) REFERENCES TypeDefinition(type_id) ON DELETE CASCADE
);

CREATE INDEX idx_enummember_type ON EnumMember(type_id);

-- ============================================================================
-- UnionMember Table
-- Stores member types for Union types
-- ============================================================================
CREATE TABLE UnionMember (
    union_member_id INTEGER PRIMARY KEY AUTOINCREMENT,
    type_id INTEGER NOT NULL,
    member_type_name TEXT NOT NULL,
    ordinal INTEGER NOT NULL, -- Position in union
    FOREIGN KEY (type_id) REFERENCES TypeDefinition(type_id) ON DELETE CASCADE
);

CREATE INDEX idx_unionmember_type ON UnionMember(type_id);

-- ============================================================================
-- TypeLink Table
-- Stores relationships and references between types
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
    source_id INTEGER NOT NULL,
    target_type_name TEXT NOT NULL,
    category TEXT CHECK(category IN ('internal', 'external')) DEFAULT 'internal',
    record_name TEXT, -- For internal links
    package_org TEXT, -- For external links
    package_name TEXT,
    package_version TEXT
);

CREATE INDEX idx_typelink_source ON TypeLink(source_context, source_id);
CREATE INDEX idx_typelink_target ON TypeLink(target_type_name);

-- ============================================================================
-- ClientDefinition Table
-- Stores client definitions
-- ============================================================================
CREATE TABLE ClientDefinition (
    client_id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    description TEXT,
    package_id INTEGER NOT NULL,
    metadata JSON,
    FOREIGN KEY (package_id) REFERENCES Package(package_id) ON DELETE CASCADE,
    UNIQUE(name, package_id)
);

CREATE INDEX idx_client_package ON ClientDefinition(package_id);
CREATE INDEX idx_client_name ON ClientDefinition(name);

-- ============================================================================
-- Function Table
-- Stores functions, methods, remote functions, and resources
-- ============================================================================
CREATE TABLE Function (
    function_id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT CHECK(kind IN ('FUNCTION', 'CONNECTOR', 'REMOTE', 'RESOURCE')) NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    package_id INTEGER,
    client_id INTEGER, -- Optional: if this function belongs to a client
    return_type JSON,
    resource_path TEXT NOT NULL,
    return_error INTEGER CHECK(return_error IN (0, 1)) DEFAULT 0,
    inferred_return_type INTEGER CHECK(inferred_return_type IN (0, 1)) DEFAULT 0,
    import_statements TEXT,
    FOREIGN KEY (package_id) REFERENCES Package(package_id) ON DELETE CASCADE,
    FOREIGN KEY (client_id) REFERENCES ClientDefinition(client_id) ON DELETE CASCADE
);

CREATE INDEX idx_function_client ON Function(client_id);

-- ============================================================================
-- FunctionConnector Table
-- Maps connector actions to connectors
-- ============================================================================
CREATE TABLE FunctionConnector (
    function_id INTEGER,
    connector_id INTEGER,
    PRIMARY KEY (function_id, connector_id),
    FOREIGN KEY (function_id) REFERENCES Function(function_id) ON DELETE CASCADE,
    FOREIGN KEY (connector_id) REFERENCES Function(function_id) ON DELETE CASCADE
);

-- ============================================================================
-- Parameter Table
-- Stores function parameters
-- ============================================================================
CREATE TABLE Parameter (
    parameter_id INTEGER PRIMARY KEY AUTOINCREMENT,
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
    type JSON NOT NULL,
    placeholder TEXT,
    default_value TEXT,
    optional INTEGER CHECK(optional IN (0, 1)) DEFAULT 0,
    import_statements TEXT,
    function_id INTEGER NOT NULL,
    FOREIGN KEY (function_id) REFERENCES Function(function_id) ON DELETE CASCADE
);

-- ============================================================================
-- ParameterMemberType Table
-- Stores member type information for complex parameters
-- ============================================================================
CREATE TABLE ParameterMemberType (
    member_id INTEGER PRIMARY KEY AUTOINCREMENT,
    type JSON NOT NULL,
    kind TEXT,
    parameter_id INTEGER NOT NULL,
    package_identifier TEXT, -- format: org:name:version
    package_name TEXT,
    FOREIGN KEY (parameter_id) REFERENCES Parameter(parameter_id) ON DELETE CASCADE
);

-- ============================================================================
-- ClassMethod Table
-- Stores methods for Class types
-- ============================================================================
CREATE TABLE ClassMethod (
    method_id INTEGER PRIMARY KEY AUTOINCREMENT,
    class_type_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    method_type TEXT CHECK(method_type IN (
        'Constructor',
        'Method',
        'Remote Function',
        'Resource Function'
    )) NOT NULL,
    return_type JSON,
    return_error INTEGER CHECK(return_error IN (0, 1)) DEFAULT 0,
    inferred_return_type INTEGER CHECK(inferred_return_type IN (0, 1)) DEFAULT 0,
    import_statements TEXT,
    FOREIGN KEY (class_type_id) REFERENCES TypeDefinition(type_id) ON DELETE CASCADE
);

CREATE INDEX idx_classmethod_type ON ClassMethod(class_type_id);
CREATE INDEX idx_classmethod_name ON ClassMethod(name);

-- ============================================================================
-- ClassMethodParameter Table
-- Stores parameters for class methods
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
    type JSON NOT NULL,
    placeholder TEXT,
    default_value TEXT,
    optional INTEGER CHECK(optional IN (0, 1)) DEFAULT 0,
    import_statements TEXT,
    FOREIGN KEY (method_id) REFERENCES ClassMethod(method_id) ON DELETE CASCADE
);

CREATE INDEX idx_classmethodparam_method ON ClassMethodParameter(method_id);

-- ============================================================================
-- ServiceDefinition Table
-- Stores service definitions with instructions
-- ============================================================================
CREATE TABLE ServiceDefinition (
    service_id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_id INTEGER NOT NULL,
    service_type TEXT NOT NULL, -- 'generic', 'http', 'grpc', etc.
    instructions TEXT, -- Markdown instructions for writing services
    test_instructions TEXT, -- Markdown instructions for test generation
    listener_config JSON, -- {"name": "Listener", "parameters": [...]}
    metadata JSON,
    FOREIGN KEY (package_id) REFERENCES Package(package_id) ON DELETE CASCADE
);

CREATE INDEX idx_service_package ON ServiceDefinition(package_id);
CREATE INDEX idx_service_type ON ServiceDefinition(service_type);

-- ============================================================================
-- Schema Migrations Table
-- Tracks applied migrations
-- ============================================================================
CREATE TABLE schema_migrations (
    version INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    applied_at TEXT DEFAULT CURRENT_TIMESTAMP,
    description TEXT
);

-- Record initial schema version
INSERT INTO schema_migrations (version, name, description)
VALUES (2, 'enhanced_schema', 'Complete enhanced schema with type definitions');
