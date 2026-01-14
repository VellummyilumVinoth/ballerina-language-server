-- Drop tables if they already exist to prevent conflicts
DROP TABLE IF EXISTS TypeLink;
DROP TABLE IF EXISTS ClassMethod;
DROP TABLE IF EXISTS UnionMember;
DROP TABLE IF EXISTS EnumMember;
DROP TABLE IF EXISTS RecordField;
DROP TABLE IF EXISTS ServiceDefinition;
DROP TABLE IF EXISTS ClientDefinition;
DROP TABLE IF EXISTS TypeDefinition;
DROP TABLE IF EXISTS FunctionConnector;
DROP TABLE IF EXISTS ParameterMemberType;
DROP TABLE IF EXISTS Parameter;
DROP TABLE IF EXISTS Function;
DROP TABLE IF EXISTS Connector;
DROP TABLE IF EXISTS Package;

-- Create Package table
CREATE TABLE Package
(
    package_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    package_name TEXT NOT NULL,
    module_name  TEXT NOT NULL,
    org          TEXT NOT NULL,
    version      TEXT,
    keywords     TEXT
);

-- Create Function table
CREATE TABLE Function
(
    function_id          INTEGER PRIMARY KEY AUTOINCREMENT,
    kind                 TEXT CHECK (kind IN ('FUNCTION', 'CONNECTOR', 'REMOTE', 'RESOURCE')),
    name                 TEXT NOT NULL,
    description          TEXT,
    package_id           INTEGER,
    return_type          JSON,                                   -- JSON type for return type information
    resource_path        TEXT NOT NULL,
    return_error         INTEGER CHECK (return_error IN (0, 1)),
    inferred_return_type INTEGER CHECK (return_error IN (0, 1)), -- Whether the return type is inferred
    import_statements    TEXT,                                   -- Import statements for the return type
    FOREIGN KEY (package_id) REFERENCES Package (package_id) ON DELETE CASCADE
);

-- Create FunctionConnector table to define actions for connectors
CREATE TABLE FunctionConnector
(
    function_id  INTEGER,
    connector_id INTEGER,
    PRIMARY KEY (function_id, connector_id),
    FOREIGN KEY (function_id) REFERENCES Function (function_id) ON DELETE CASCADE,
    FOREIGN KEY (connector_id) REFERENCES Function (function_id) ON DELETE CASCADE
);

-- Create Parameter table
CREATE TABLE Parameter
(
    parameter_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    name              TEXT NOT NULL,
    description       TEXT,
    label             TEXT,
    kind              TEXT CHECK (kind IN
                                  ('REQUIRED', 'DEFAULTABLE', 'INCLUDED_RECORD', 'REST_PARAMETER', 'INCLUDED_FIELD',
                                   'INCLUDED_RECORD_REST', 'PARAM_FOR_TYPE_INFER', 'PATH_PARAM', 'PATH_REST_PARAM')),
    type              JSON, -- JSON type for parameter type information
    placeholder       TEXT,
    default_value     TEXT,
    optional          INTEGER CHECK (optional IN (0, 1)),
    import_statements TEXT,
    function_id       INTEGER,
    FOREIGN KEY (function_id) REFERENCES Function (function_id) ON DELETE CASCADE
);

-- Create Parameter Member Type table
CREATE TABLE ParameterMemberType
(
    member_id          INTEGER PRIMARY KEY AUTOINCREMENT,
    type               JSON, -- JSON type for parameter type information
    kind               TEXT,
    parameter_id       INTEGER,
    package_identifier TEXT, -- Format of the package is org:name:version
    package_name       TEXT,
    FOREIGN KEY (parameter_id) REFERENCES Parameter (parameter_id) ON DELETE CASCADE
);

-- Create TypeDefinition table
CREATE TABLE TypeDefinition
(
    type_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT                                                                                        NOT NULL,
    description   TEXT,
    type_category TEXT CHECK (type_category IN
                              ('Record', 'Enum', 'Union', 'Class', 'Error', 'Constant', 'typedesc', 'Other')) NOT NULL,
    package_id    INTEGER                                                                                     NOT NULL,
    base_type     TEXT, -- For typedesc
    FOREIGN KEY (package_id) REFERENCES Package (package_id) ON DELETE CASCADE
);

-- Create RecordField table for fields within record types
CREATE TABLE RecordField
(
    field_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    type_id       INTEGER NOT NULL,
    name          TEXT    NOT NULL,
    description   TEXT,
    field_type    JSON    NOT NULL, -- JSON type for field type information
    optional      INTEGER CHECK (optional IN (0, 1)) DEFAULT 0,
    FOREIGN KEY (type_id) REFERENCES TypeDefinition (type_id) ON DELETE CASCADE
);

-- Create EnumMember table for members of enum types
CREATE TABLE EnumMember
(
    member_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    type_id     INTEGER NOT NULL,
    name        TEXT    NOT NULL,
    description TEXT,
    ordinal     INTEGER NOT NULL, -- Position in enum
    FOREIGN KEY (type_id) REFERENCES TypeDefinition (type_id) ON DELETE CASCADE
);

-- Create UnionMember table for member types of union types
CREATE TABLE UnionMember
(
    union_member_id  INTEGER PRIMARY KEY AUTOINCREMENT,
    type_id          INTEGER NOT NULL,
    member_type_name TEXT    NOT NULL,
    ordinal          INTEGER NOT NULL, -- Position in union
    FOREIGN KEY (type_id) REFERENCES TypeDefinition (type_id) ON DELETE CASCADE
);

-- Create ClassMethod table for methods in class types
CREATE TABLE ClassMethod
(
    method_id     INTEGER PRIMARY KEY AUTOINCREMENT,
    class_type_id INTEGER NOT NULL,
    function_id   INTEGER NOT NULL, -- References existing Function table
    method_type   TEXT CHECK (method_type IN ('Constructor', 'Method', 'Remote Function', 'Resource Function')),
    FOREIGN KEY (class_type_id) REFERENCES TypeDefinition (type_id) ON DELETE CASCADE,
    FOREIGN KEY (function_id) REFERENCES Function (function_id) ON DELETE CASCADE
);

-- Create ClientDefinition table for client metadata
CREATE TABLE ClientDefinition
(
    client_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL,
    description TEXT,
    package_id  INTEGER NOT NULL,
    FOREIGN KEY (package_id) REFERENCES Package (package_id) ON DELETE CASCADE
);

-- Create ServiceDefinition table for service templates and instructions
CREATE TABLE ServiceDefinition
(
    service_id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    package_id                  INTEGER NOT NULL,
    service_type                TEXT    NOT NULL, -- e.g., "generic", "http", "graphql"
    instructions                TEXT,             -- Markdown instructions for service creation
    listener_name               TEXT,
    listener_config             JSON,             -- JSON for listener parameters
    test_generation_instruction TEXT,             -- Test generation instructions
    FOREIGN KEY (package_id) REFERENCES Package (package_id) ON DELETE CASCADE
);

-- Create TypeLink table for cross-package and intra-package type dependencies
CREATE TABLE TypeLink
(
    link_id          INTEGER PRIMARY KEY AUTOINCREMENT,
    source_field_id  INTEGER, -- RecordField that has this link
    target_type_name TEXT                                              NOT NULL,
    category         TEXT CHECK (category IN ('internal', 'external')) NOT NULL,
    package_org      TEXT,    -- For external links
    package_name     TEXT,    -- For external links
    FOREIGN KEY (source_field_id) REFERENCES RecordField (field_id) ON DELETE CASCADE
);