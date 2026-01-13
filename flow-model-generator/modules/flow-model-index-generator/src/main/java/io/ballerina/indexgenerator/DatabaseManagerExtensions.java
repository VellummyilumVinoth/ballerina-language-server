/*
 *  Copyright (c) 2024, WSO2 LLC. (http://www.wso2.com)
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.indexgenerator;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Extension methods for DatabaseManager to support new type definition tables.
 * This class should be merged into DatabaseManager or used as a mixin.
 *
 * @since 2.0.0
 */
public class DatabaseManagerExtensions {

    private static final Logger LOGGER = Logger.getLogger(DatabaseManagerExtensions.class.getName());
    private static final String INDEX_FILE_NAME = "central-index.sqlite";
    private static final String dbPath = getDatabasePath();

    private static String getDatabasePath() {
        String destinationPath =
                Path.of("flow-model-generator/modules/flow-model-generator-ls-extension/src/main/resources")
                        .resolve(INDEX_FILE_NAME)
                        .toString();
        return "jdbc:sqlite:" + destinationPath;
    }

    private static int insertEntry(String sql, Object[] params) {
        try (Connection conn = DriverManager.getConnection(dbPath);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            stmt.executeUpdate();
            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getInt(1);
                } else {
                    throw new SQLException("Insert failed, no ID obtained.");
                }
            }
        } catch (SQLException e) {
            LOGGER.severe("Error executing query: " + e.getMessage());
            return -1;
        }
    }

    // ========================================================================
    // TypeDefinition Methods
    // ========================================================================

    /**
     * Inserts a type definition into the database.
     *
     * @param packageId     Package ID
     * @param name          Type name
     * @param description   Type description
     * @param typeCategory  Type category (Record, Enum, Union, Class, etc.)
     * @param value         Value (for constants)
     * @param baseType      Base type (for typedesc)
     * @param metadata      Additional metadata as JSON
     * @return Type ID
     */
    public static int insertTypeDefinition(int packageId, String name, String description,
                                          String typeCategory, String value, String baseType,
                                          String metadata) {
        String sql = "INSERT INTO TypeDefinition (package_id, name, description, type_category, " +
                "value, base_type, metadata) VALUES (?, ?, ?, ?, ?, ?, ?)";
        return insertEntry(sql, new Object[]{packageId, name, description, typeCategory,
                value, baseType, metadata});
    }

    // ========================================================================
    // RecordField Methods
    // ========================================================================

    /**
     * Inserts a record field into the database.
     *
     * @param typeId       Type ID of the parent record
     * @param name         Field name
     * @param description  Field description
     * @param fieldType    Field type as JSON
     * @param defaultValue Default value
     * @param optional     Whether the field is optional (0 or 1)
     * @param ordinal      Field position
     * @return Field ID
     */
    public static int insertRecordField(int typeId, String name, String description,
                                       String fieldType, String defaultValue, int optional,
                                       int ordinal) {
        String sql = "INSERT INTO RecordField (type_id, name, description, field_type, " +
                "default_value, optional, ordinal) VALUES (?, ?, ?, ?, ?, ?, ?)";
        return insertEntry(sql, new Object[]{typeId, name, description, fieldType,
                defaultValue, optional, ordinal});
    }

    // ========================================================================
    // EnumMember Methods
    // ========================================================================

    /**
     * Inserts an enum member into the database.
     *
     * @param typeId      Type ID of the parent enum
     * @param name        Member name
     * @param description Member description
     * @param ordinal     Member position
     * @return Member ID
     */
    public static int insertEnumMember(int typeId, String name, String description, int ordinal) {
        String sql = "INSERT INTO EnumMember (type_id, name, description, ordinal) " +
                "VALUES (?, ?, ?, ?)";
        return insertEntry(sql, new Object[]{typeId, name, description, ordinal});
    }

    // ========================================================================
    // UnionMember Methods
    // ========================================================================

    /**
     * Inserts a union member into the database.
     *
     * @param typeId         Type ID of the parent union
     * @param memberTypeName Type name of the union member
     * @param ordinal        Member position
     * @return Member ID
     */
    public static int insertUnionMember(int typeId, String memberTypeName, int ordinal) {
        String sql = "INSERT INTO UnionMember (type_id, member_type_name, ordinal) " +
                "VALUES (?, ?, ?)";
        return insertEntry(sql, new Object[]{typeId, memberTypeName, ordinal});
    }

    // ========================================================================
    // TypeLink Methods
    // ========================================================================

    /**
     * Inserts a type link into the database.
     *
     * @param sourceContext  Context of the source (record_field, parameter, etc.)
     * @param sourceId       ID of the source
     * @param targetTypeName Name of the target type
     * @param category       Link category (internal, external)
     * @param recordName     Record name (for internal links)
     * @param packageOrg     Package organization (for external links)
     * @param packageName    Package name (for external links)
     * @param packageVersion Package version (for external links)
     * @return Link ID
     */
    public static int insertTypeLink(String sourceContext, int sourceId, String targetTypeName,
                                    String category, String recordName, String packageOrg,
                                    String packageName, String packageVersion) {
        String sql = "INSERT INTO TypeLink (source_context, source_id, target_type_name, " +
                "category, record_name, package_org, package_name, package_version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        return insertEntry(sql, new Object[]{sourceContext, sourceId, targetTypeName,
                category, recordName, packageOrg, packageName, packageVersion});
    }

    // ========================================================================
    // ClientDefinition Methods
    // ========================================================================

    /**
     * Inserts a client definition into the database.
     *
     * @param packageId   Package ID
     * @param name        Client name
     * @param description Client description
     * @param metadata    Additional metadata as JSON
     * @return Client ID
     */
    public static int insertClientDefinition(int packageId, String name, String description,
                                            String metadata) {
        String sql = "INSERT INTO ClientDefinition (package_id, name, description, metadata) " +
                "VALUES (?, ?, ?, ?)";
        return insertEntry(sql, new Object[]{packageId, name, description, metadata});
    }

    // ========================================================================
    // ServiceDefinition Methods
    // ========================================================================

    /**
     * Inserts a service definition into the database.
     *
     * @param packageId         Package ID
     * @param serviceType       Service type (generic, http, grpc, etc.)
     * @param instructions      Service writing instructions
     * @param testInstructions  Test generation instructions
     * @param listenerConfig    Listener configuration as JSON
     * @param metadata          Additional metadata as JSON
     * @return Service ID
     */
    public static int insertServiceDefinition(int packageId, String serviceType,
                                             String instructions, String testInstructions,
                                             String listenerConfig, String metadata) {
        String sql = "INSERT INTO ServiceDefinition (package_id, service_type, instructions, " +
                "test_instructions, listener_config, metadata) VALUES (?, ?, ?, ?, ?, ?)";
        return insertEntry(sql, new Object[]{packageId, serviceType, instructions,
                testInstructions, listenerConfig, metadata});
    }

    // ========================================================================
    // ClassMethod Methods
    // ========================================================================

    /**
     * Inserts a class method into the database.
     *
     * @param classTypeId        Type ID of the parent class
     * @param name               Method name
     * @param description        Method description
     * @param methodType         Method type (Constructor, Method, Remote Function, etc.)
     * @param returnType         Return type as JSON
     * @param returnError        Whether the method returns an error (0 or 1)
     * @param inferredReturnType Whether the return type is inferred (0 or 1)
     * @param importStatements   Import statements
     * @return Method ID
     */
    public static int insertClassMethod(int classTypeId, String name, String description,
                                       String methodType, String returnType, int returnError,
                                       int inferredReturnType, String importStatements) {
        String sql = "INSERT INTO ClassMethod (class_type_id, name, description, method_type, " +
                "return_type, return_error, inferred_return_type, import_statements) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        return insertEntry(sql, new Object[]{classTypeId, name, description, methodType,
                returnType, returnError, inferredReturnType, importStatements});
    }

    // ========================================================================
    // ClassMethodParameter Methods
    // ========================================================================

    /**
     * Inserts a class method parameter into the database.
     *
     * @param methodId         Method ID
     * @param paramName        Parameter name
     * @param paramDescription Parameter description
     * @param paramLabel       Parameter label
     * @param paramType        Parameter type as JSON
     * @param placeholder      Placeholder value
     * @param defaultValue     Default value
     * @param parameterKind    Parameter kind
     * @param optional         Whether the parameter is optional (0 or 1)
     * @param importStatements Import statements
     * @return Parameter ID
     */
    public static int insertClassMethodParameter(int methodId, String paramName,
                                                 String paramDescription, String paramLabel,
                                                 String paramType, String placeholder,
                                                 String defaultValue, String parameterKind,
                                                 int optional, String importStatements) {
        String sql = "INSERT INTO ClassMethodParameter (method_id, name, description, label, " +
                "type, placeholder, default_value, kind, optional, import_statements) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return insertEntry(sql, new Object[]{methodId, paramName, paramDescription, paramLabel,
                paramType, placeholder, defaultValue, parameterKind, optional, importStatements});
    }

    // ========================================================================
    // Update Package Description
    // ========================================================================

    /**
     * Updates the description for a package.
     *
     * @param packageId   Package ID
     * @param description Package description
     */
    public static void updatePackageDescription(int packageId, String description) {
        String sql = "UPDATE Package SET description = ? WHERE package_id = ?";
        try (Connection conn = DriverManager.getConnection(dbPath);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, description);
            stmt.setInt(2, packageId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.severe("Error updating package description: " + e.getMessage());
        }
    }

    // ========================================================================
    // Update Function with Client ID
    // ========================================================================

    /**
     * Updates a function to associate it with a client.
     *
     * @param functionId Function ID
     * @param clientId   Client ID
     */
    public static void updateFunctionClientId(int functionId, int clientId) {
        String sql = "UPDATE Function SET client_id = ? WHERE function_id = ?";
        try (Connection conn = DriverManager.getConnection(dbPath);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, clientId);
            stmt.setInt(2, functionId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.severe("Error updating function client_id: " + e.getMessage());
        }
    }
}
