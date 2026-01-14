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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.ClassSymbol;
import io.ballerina.compiler.api.symbols.Documentable;
import io.ballerina.compiler.api.symbols.Documentation;
import io.ballerina.compiler.api.symbols.EnumSymbol;
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.MethodSymbol;
import io.ballerina.compiler.api.symbols.Qualifier;
import io.ballerina.compiler.api.symbols.RecordFieldSymbol;
import io.ballerina.compiler.api.symbols.RecordTypeSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.api.symbols.TypeDefinitionSymbol;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.api.symbols.UnionTypeSymbol;
import io.ballerina.modelgenerator.commons.CommonUtils;
import io.ballerina.modelgenerator.commons.FunctionData;
import io.ballerina.modelgenerator.commons.FunctionDataBuilder;
import io.ballerina.modelgenerator.commons.ModuleInfo;
import io.ballerina.modelgenerator.commons.PackageUtil;
import io.ballerina.modelgenerator.commons.ParameterData;
import io.ballerina.modelgenerator.commons.ParameterMemberTypeData;
import io.ballerina.projects.Module;
import io.ballerina.projects.ModuleDescriptor;
import io.ballerina.projects.Package;
import io.ballerina.projects.directory.BuildProject;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Logger;

/**
 * Index generator to cache functions and connectors.
 *
 * @since 1.0.0
 */
class IndexGenerator {

    private static final java.lang.reflect.Type typeToken =
            new TypeToken<Map<String, List<PackageListGenerator.PackageMetadataInfo>>>() {
            }.getType();
    private static final Logger LOGGER = Logger.getLogger(IndexGenerator.class.getName());

    public static void main(String[] args) {
        DatabaseManager.createDatabase();
        BuildProject buildProject = PackageUtil.getSampleProject();

        Gson gson = new Gson();
        URL resource = IndexGenerator.class.getClassLoader().getResource(PackageListGenerator.PACKAGE_JSON_FILE);
        try (InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(resource).openStream(),
                StandardCharsets.UTF_8)) {
            Map<String, List<PackageListGenerator.PackageMetadataInfo>> packagesMap = gson.fromJson(reader,
                    typeToken);
            ForkJoinPool forkJoinPool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
            forkJoinPool.submit(() -> packagesMap.forEach((key, value) -> value.forEach(
                    packageMetadataInfo -> resolvePackage(buildProject, key, packageMetadataInfo)))).join();
        } catch (IOException e) {
            LOGGER.severe("Error reading packages JSON file: " + e.getMessage());
        }

        // TODO: Remove this once thw raw parameter property type is introduced
        DatabaseManager.executeQuery(
                "UPDATE Parameter SET default_value = '``', placeholder='``' WHERE type = 'sql:ParameterizedQuery'");
//
        // TODO: Remove this once the package index is introduced
        DatabaseManager.executeQuery(
                "UPDATE Parameter SET type= 'anydata', default_value= 'anydata', placeholder= 'anydata' \n" +
                        "WHERE parameter_id IN (\n" +
                        "    SELECT p.parameter_id\n" +
                        "    FROM Parameter p\n" +
                        "    INNER JOIN Function f ON f.function_id = p.function_id\n" +
                        "    INNER JOIN Package pack ON pack.package_id = f.package_id\n" +
                        "    WHERE pack.package_name = 'http' AND p.name = 'targetType'\n" +
                        ");");

        // TODO: Need to improve how we handle lang lib functions
        DatabaseManager.updateTypeParameter("lang.array", "array:Type1", "(any|error)");
        DatabaseManager.updateTypeParameter("lang.array", "array:Type", "(any|error)");
        DatabaseManager.updateTypeParameter("lang.array", "array:AnydataType", "(anydata|error)");
        DatabaseManager.updateTypeParameter("lang.error", "error:DetailType", "error:Detail");
        DatabaseManager.updateTypeParameter("lang.map", "map:Type1", "map<any|error>");
        DatabaseManager.updateTypeParameter("lang.map", "map:Type", "map<any|error>");
        DatabaseManager.updateTypeParameter("lang.stream", "stream:Type1", "(any|error)");
        DatabaseManager.updateTypeParameter("lang.stream", "stream:Type", "(any|error)");
        DatabaseManager.updateTypeParameter("lang.stream", "stream:ErrorType", "error");
        DatabaseManager.updateTypeParameter("lang.stream", "stream:CompletionType", "error");
        DatabaseManager.updateTypeParameter("lang.xml", "xml:XmlType", "xml");
        DatabaseManager.updateTypeParameter("lang.xml", "xml:ItemType",
                "(xml:Element|xml:Comment|xml:ProcessingInstruction|xml:Text)");
        DatabaseManager.updateTypeParameter("lang.table", "table:MapType1", "map<any|error>");
        DatabaseManager.updateTypeParameter("lang.table", "table:MapType", "map<any|error>");
        DatabaseManager.updateTypeParameter("lang.table", "table:KeyType", "anydata");
        DatabaseManager.updateTypeParameter("lang.table", "table:Type", "(any|error)");
        DatabaseManager.updateTypeParameter("lang.value", "value:AnydataType", "anydata");
        DatabaseManager.updateTypeParameter("lang.value", "value:Type", "(any|error)");
    }

    private static void resolvePackage(BuildProject buildProject, String org,
                                       PackageListGenerator.PackageMetadataInfo packageMetadataInfo) {
        Package resolvedPackage;
        try {
            resolvedPackage = Objects.requireNonNull(PackageUtil.getModulePackage(buildProject, org,
                    packageMetadataInfo.name(), packageMetadataInfo.version())).orElseThrow();
        } catch (Throwable e) {
            LOGGER.severe("Error resolving package: " + packageMetadataInfo.name() + e.getMessage());
            return;
        }

        List<String> exportedModules = resolvedPackage.manifest().exportedModules();
        for (Module module : resolvedPackage.modules()) {
            if (exportedModules.contains(module.descriptor().name().toString())) {
                processModule(resolvedPackage, module);
            }
        }
    }

    private static void processModule(Package resolvedPackage, Module module) {
        ModuleDescriptor descriptor = module.descriptor();
        String moduleName = descriptor.name().toString();
        LOGGER.info("Processing package: " + moduleName);
        int packageId = DatabaseManager.insertPackage(descriptor.org().value(),
                module.packageInstance().packageName().value(), moduleName,
                descriptor.version().value().toString(), resolvedPackage.manifest().keywords());
        if (packageId == -1) {
            LOGGER.severe("Error inserting package to database: " + moduleName);
            return;
        }

        SemanticModel semanticModel;
        try {
            semanticModel = PackageUtil.getCompilation(resolvedPackage)
                    .getSemanticModel(module.moduleId());
        } catch (Exception e) {
            LOGGER.severe("Error reading semantic model: " + e.getMessage());
            return;
        }

        TypeSymbol errorTypeSymbol = semanticModel.types().ERROR;

        for (Symbol symbol : semanticModel.moduleSymbols()) {
            // Process type definitions (records, enums, unions, etc.)
            if (symbol.kind() == SymbolKind.TYPE_DEFINITION) {
                TypeDefinitionSymbol typeDefSymbol = (TypeDefinitionSymbol) symbol;
                if (!typeDefSymbol.qualifiers().contains(Qualifier.PUBLIC)) {
                    continue;
                }
                processTypeDefinition(typeDefSymbol, packageId, semanticModel, module);
                continue;
            }

            // Process enum types
            if (symbol.kind() == SymbolKind.ENUM) {
                EnumSymbol enumSymbol = (EnumSymbol) symbol;
                if (!enumSymbol.qualifiers().contains(Qualifier.PUBLIC)) {
                    continue;
                }
                processEnumType(enumSymbol, packageId);
                continue;
            }

            // Process service declarations
            if (symbol.kind() == SymbolKind.SERVICE_DECLARATION) {
                processServiceDeclaration(symbol, packageId);
                continue;
            }

            if (symbol.kind() == SymbolKind.FUNCTION) {
                FunctionSymbol functionSymbol = (FunctionSymbol) symbol;
                if (!functionSymbol.qualifiers().contains(Qualifier.PUBLIC)) {
                    continue;
                }

                processFunctionSymbol(semanticModel, functionSymbol, functionSymbol, packageId, FunctionType.FUNCTION,
                        moduleName, errorTypeSymbol, module);
                continue;
            }
            if (symbol.kind() == SymbolKind.CLASS) {
                ClassSymbol classSymbol = (ClassSymbol) symbol;

                // Process all public classes (not just clients)
                if (!classSymbol.qualifiers().contains(Qualifier.PUBLIC)) {
                    continue;
                }

                // Insert class as TypeDefinition
                Optional<String> classNameOpt = classSymbol.getName();
                if (classNameOpt.isEmpty()) {
                    continue;
                }
                String className = classNameOpt.get();
                String classDescription = getDescription(classSymbol);

                int classTypeId = DatabaseManager.insertTypeDefinition(
                    packageId, className, classDescription, "Class", null
                );

                if (classTypeId == -1) {
                    LOGGER.warning("Failed to insert class type: " + className);
                    continue;
                }

                // Special handling for Client classes
                boolean isClient = classSymbol.nameEquals("Client");
                int clientId = -1;

                if (isClient) {
                    clientId = DatabaseManager.insertClientDefinition(packageId, className, classDescription);
                    if (clientId == -1) {
                        LOGGER.warning("Failed to insert client definition: " + className);
                    }
                }

                // Process init method if present
                Optional<MethodSymbol> initMethodSymbol = classSymbol.initMethod();
                if (initMethodSymbol.isPresent()) {
                    FunctionType funcType = isClient ? FunctionType.CONNECTOR : FunctionType.FUNCTION;
                    int functionId = processFunctionSymbol(
                        semanticModel, initMethodSymbol.get(), classSymbol,
                        packageId, funcType, moduleName, errorTypeSymbol, module
                    );

                    if (functionId != -1) {
                        // Link method to class via ClassMethod table
                        DatabaseManager.insertClassMethod(classTypeId, functionId, "Constructor");

                        if (isClient && clientId != -1) {
                            DatabaseManager.mapConnectorAction(functionId, clientId);
                        }
                    }
                }

                // Process all public methods
                Map<String, MethodSymbol> methods = classSymbol.methods();
                for (Map.Entry<String, MethodSymbol> entry : methods.entrySet()) {
                    MethodSymbol methodSymbol = entry.getValue();
                    List<Qualifier> qualifiers = methodSymbol.qualifiers();

                    if (!qualifiers.contains(Qualifier.PUBLIC) &&
                        !qualifiers.contains(Qualifier.REMOTE) &&
                        !qualifiers.contains(Qualifier.RESOURCE)) {
                        continue;
                    }

                    FunctionType functionType;
                    String methodType;

                    if (qualifiers.contains(Qualifier.REMOTE)) {
                        functionType = FunctionType.REMOTE;
                        methodType = "Remote Function";
                    } else if (qualifiers.contains(Qualifier.RESOURCE)) {
                        functionType = FunctionType.RESOURCE;
                        methodType = "Resource Function";
                    } else {
                        functionType = FunctionType.FUNCTION;
                        methodType = "Method";
                    }

                    int functionId = processFunctionSymbol(
                        semanticModel, methodSymbol, methodSymbol,
                        packageId, functionType, moduleName, errorTypeSymbol, module
                    );

                    if (functionId != -1) {
                        // Link method to class via ClassMethod table
                        DatabaseManager.insertClassMethod(classTypeId, functionId, methodType);

                        if (isClient && clientId != -1) {
                            DatabaseManager.mapConnectorAction(functionId, clientId);
                        }
                    }
                }
            }
        }
    }

    private static int processFunctionSymbol(SemanticModel semanticModel, FunctionSymbol functionSymbol,
                                             Documentable documentable, int packageId,
                                             FunctionType functionType, String packageName,
                                             TypeSymbol errorTypeSymbol, Module module) {
        // Capture the name of the function
        Optional<String> name = functionSymbol.getName();
        if (name.isEmpty()) {
            return packageId;
        }

        // Create ModuleInfo for the function
        ModuleInfo moduleInfo = ModuleInfo.from(module.descriptor());

        // Determine function kind based on function type
        FunctionData.Kind functionKind = mapFunctionTypeToKind(functionType);

        // Use FunctionDataBuilder to create FunctionData
        FunctionDataBuilder functionDataBuilder = new FunctionDataBuilder()
                .enableIndex()
                .semanticModel(semanticModel)
                .functionSymbol(functionSymbol)
                .moduleInfo(moduleInfo)
                .functionResultKind(functionKind);

        // Handle special cases for connectors and class symbols
        if (documentable instanceof ClassSymbol classSymbol) {
            functionDataBuilder.parentSymbol(classSymbol);
            if (name.get().equals("init")) {
                functionDataBuilder.name("Client");
            } else {
                functionDataBuilder.name(name.get());
            }
        } else {
            functionDataBuilder.name(name.get());
        }

        // Build the function data
        FunctionData functionData = functionDataBuilder.build();

        // Insert function into database
        String resourcePath = functionData.resourcePath() != null ? functionData.resourcePath() : "";
        int functionId = DatabaseManager.insertFunction(packageId, functionData.name(),
                functionData.description(), functionData.returnType(),
                functionData.kind().name(), resourcePath,
                functionData.returnError() ? 1 : 0, functionData.inferredReturnType(),
                functionData.importStatements());

        // Insert parameters into database
        for (Map.Entry<String, ParameterData> entry : functionData.parameters().entrySet()) {
            ParameterData parameterData = entry.getValue();
            int paramId = DatabaseManager.insertFunctionParameter(functionId, parameterData.name(),
                    parameterData.description(), parameterData.type(), parameterData.placeholder(),
                    parameterData.defaultValue(),
                    FunctionParameterKind.fromString(parameterData.kind().name()),
                    parameterData.optional() ? 1 : 0, parameterData.importStatements(), parameterData.label());

            // Insert parameter member types
            insertParameterMemberTypesFromParameterData(paramId, parameterData);
        }

        return functionId;
    }

    private static FunctionData.Kind mapFunctionTypeToKind(FunctionType functionType) {
        return switch (functionType) {
            case FUNCTION -> FunctionData.Kind.FUNCTION;
            case REMOTE -> FunctionData.Kind.REMOTE;
            case CONNECTOR -> FunctionData.Kind.CONNECTOR;
            case RESOURCE -> FunctionData.Kind.RESOURCE;
        };
    }

    private static void insertParameterMemberTypesFromParameterData(int parameterId, ParameterData parameterData) {
        for (ParameterMemberTypeData memberType : parameterData.typeMembers()) {
            DatabaseManager.insertParameterMemberType(parameterId, memberType.type(), memberType.kind(),
                    memberType.packageInfo(), memberType.packageName());
        }
    }

    enum FunctionType {
        FUNCTION,
        REMOTE,
        CONNECTOR,
        RESOURCE
    }

    enum FunctionParameterKind {
        REQUIRED,
        DEFAULTABLE,
        INCLUDED_RECORD,
        REST_PARAMETER,
        INCLUDED_FIELD,
        PARAM_FOR_TYPE_INFER,
        INCLUDED_RECORD_REST,
        PATH_PARAM,
        PATH_REST_PARAM;

        // need to have a fromString logic here
        public static FunctionParameterKind fromString(String value) {
            if (value.equals("REST")) {
                return REST_PARAMETER;
            }
            return FunctionParameterKind.valueOf(value);
        }
    }

    /**
     * Processes a type definition symbol and inserts it into the database.
     * Handles records, unions, classes, and other type definitions.
     */
    private static void processTypeDefinition(TypeDefinitionSymbol typeDefSymbol, int packageId,
                                             SemanticModel semanticModel, Module module) {
        Optional<String> nameOpt = typeDefSymbol.getName();
        if (nameOpt.isEmpty()) {
            return;
        }

        String name = nameOpt.get();
        String description = getDescription(typeDefSymbol);
        TypeSymbol typeDescriptor = typeDefSymbol.typeDescriptor();

        // Get the raw type to determine the actual type kind
        TypeSymbol rawType = CommonUtils.getRawType(typeDescriptor);
        TypeDescKind typeKind = rawType.typeKind();

        String typeCategory = mapTypeDescKindToCategory(typeKind);
        String baseType = typeDescriptor.signature();

        // Insert the type definition (value field is reserved for constant values, null for now)
        int typeId = DatabaseManager.insertTypeDefinition(packageId, name, description, typeCategory, baseType);

        if (typeId == -1) {
            LOGGER.warning("Failed to insert type definition: " + name);
            return;
        }

        // Process specific type categories
        switch (typeKind) {
            case RECORD:
                if (rawType instanceof RecordTypeSymbol recordType) {
                    processRecordType(recordType, typeId, semanticModel, module);
                }
                break;
            case UNION:
                if (rawType instanceof UnionTypeSymbol unionType) {
                    processUnionType(unionType, typeId);
                }
                break;
            case TYPE_REFERENCE:
                // Handle type aliases - for now, the base_type field contains the full signature
                // Future enhancement: could extract and store additional type alias information
                break;
            default:
                // Other types (primitives, arrays, etc.) are stored with their signature
                break;
        }
    }

    /**
     * Processes an enum symbol and inserts it into the database.
     */
    private static void processEnumType(EnumSymbol enumSymbol, int packageId) {
        Optional<String> nameOpt = enumSymbol.getName();
        if (nameOpt.isEmpty()) {
            return;
        }

        String name = nameOpt.get();
        String description = getDescription(enumSymbol);

        // Insert the enum as a type definition
        int typeId = DatabaseManager.insertTypeDefinition(packageId, name, description, "Enum",
                null);

        if (typeId == -1) {
            LOGGER.warning("Failed to insert enum type: " + name);
            return;
        }

        // Insert enum members
        List<io.ballerina.compiler.api.symbols.ConstantSymbol> members = enumSymbol.members();
        int ordinal = 0;
        for (io.ballerina.compiler.api.symbols.ConstantSymbol member : members) {
            Optional<String> memberNameOpt = member.getName();
            if (memberNameOpt.isEmpty()) {
                continue;
            }
            String memberName = memberNameOpt.get();
            String memberDescription = getDescription(member);
            DatabaseManager.insertEnumMember(typeId, memberName, memberDescription, ordinal++);
        }
    }

    /**
     * Processes a record type and inserts its fields into the database.
     * Also processes type links for record fields.
     */
    private static void processRecordType(RecordTypeSymbol recordType, int typeId,
                                         SemanticModel semanticModel, Module module) {
        Map<String, RecordFieldSymbol> fields = recordType.fieldDescriptors();

        for (Map.Entry<String, RecordFieldSymbol> entry : fields.entrySet()) {
            RecordFieldSymbol fieldSymbol = entry.getValue();
            Optional<String> fieldNameOpt = fieldSymbol.getName();
            if (fieldNameOpt.isEmpty()) {
                continue;
            }

            String fieldName = fieldNameOpt.get();
            String fieldDescription = getDescription(fieldSymbol);
            TypeSymbol fieldTypeSymbol = fieldSymbol.typeDescriptor();
            String fieldType = fieldTypeSymbol.signature();

            // Convert to JSON format for consistency with context.json migration
            String fieldTypeJson = String.format("{\"name\":\"%s\"}",
                                                escapeJson(fieldType));

            int optional = fieldSymbol.isOptional() ? 1 : 0;

            int fieldId = DatabaseManager.insertRecordField(typeId, fieldName, fieldDescription,
                    fieldTypeJson, optional);

            // Process type links for this field
            if (fieldId != -1) {
                processFieldTypeLinks(fieldSymbol, fieldId, semanticModel, module);
            }
        }

        // Handle rest field if present
        if (recordType.restTypeDescriptor().isPresent()) {
            TypeSymbol restType = recordType.restTypeDescriptor().get();
            String restFieldType = String.format("{\"name\":\"...%s\"}",
                                                escapeJson(restType.signature()));
            DatabaseManager.insertRecordField(typeId, "...", "Rest field", restFieldType, 0);
        }
    }

    /**
     * Processes a union type and inserts its member types into the database.
     */
    private static void processUnionType(UnionTypeSymbol unionType, int typeId) {
        List<TypeSymbol> memberTypes = unionType.memberTypeDescriptors();
        int ordinal = 0;

        for (TypeSymbol memberType : memberTypes) {
            String memberTypeName = memberType.signature();
            DatabaseManager.insertUnionMember(typeId, memberTypeName, ordinal++);
        }
    }

    /**
     * Maps TypeDescKind to type category string for database storage.
     */
    private static String mapTypeDescKindToCategory(TypeDescKind typeKind) {
        return switch (typeKind) {
            case RECORD -> "Record";
            case UNION -> "Union";
            case OBJECT -> "Class";
            case ERROR -> "Error";
            case TYPE_REFERENCE -> "Other";
            default -> "Other";
        };
    }

    /**
     * Extracts description from a documentable symbol.
     */
    private static String getDescription(Symbol symbol) {
        if (symbol instanceof Documentable documentable) {
            Optional<Documentation> docOpt = documentable.documentation();
            if (docOpt.isPresent()) {
                return docOpt.get().description().orElse("");
            }
        }
        return "";
    }

    /**
     * Escapes special characters for JSON strings.
     */
    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    /**
     * Process a service declaration and insert it into the database.
     */
    private static void processServiceDeclaration(Symbol symbol, int packageId) {
        String serviceType = "generic";
        String instructions = null;
        String testInstructions = null;
        String listenerName = null;
        String listenerConfig = null;

        // Extract service description as instructions if available
        if (symbol instanceof Documentable documentable) {
            Optional<Documentation> docOpt = documentable.documentation();
            if (docOpt.isPresent()) {
                instructions = docOpt.get().description().orElse(null);
            }
        }

        // Insert service definition
        DatabaseManager.insertServiceDefinition(
            packageId, serviceType, instructions,
            listenerName, listenerConfig, testInstructions
        );
    }

    /**
     * Process type links for a record field by analyzing its type.
     */
    private static void processFieldTypeLinks(RecordFieldSymbol fieldSymbol, int fieldId,
                                             SemanticModel semanticModel, Module module) {
        TypeSymbol fieldTypeSymbol = fieldSymbol.typeDescriptor();
        TypeSymbol fieldRawType = CommonUtils.getRawType(fieldTypeSymbol);

        // Check if field type is a record or references a record
        if (fieldRawType.typeKind() == TypeDescKind.TYPE_REFERENCE) {
            // Extract type reference information
            String targetTypeName = extractTypeName(fieldTypeSymbol.signature());
            if (targetTypeName != null && !targetTypeName.isEmpty()) {
                String category = "internal"; // Default to internal
                String packageOrg = null;
                String targetPackageName = null;

                // Check if the type belongs to the current module
                Optional<io.ballerina.compiler.api.symbols.ModuleSymbol> fieldModule =
                    fieldTypeSymbol.getModule();
                if (fieldModule.isPresent()) {
                    io.ballerina.compiler.api.symbols.ModuleSymbol fieldModuleSymbol = fieldModule.get();

                    // Compare module IDs to determine if it's external
                    String currentModuleName = module.descriptor().name().toString();
                    String currentOrgName = module.descriptor().org().value();
                    String fieldModuleName = fieldModuleSymbol.id().moduleName();
                    String fieldOrgName = fieldModuleSymbol.id().orgName();

                    // If org or module names are different, it's an external link
                    if (!currentOrgName.equals(fieldOrgName) || !currentModuleName.equals(fieldModuleName)) {
                        category = "external";
                        packageOrg = fieldOrgName;
                        targetPackageName = fieldModuleSymbol.id().packageName();
                    }
                }

                // Insert type link
                DatabaseManager.insertTypeLink(
                    fieldId, targetTypeName, category,
                    packageOrg, targetPackageName
                );
            }
        }
    }

    /**
     * Extracts the type name from a type signature.
     * For example, "ballerina/http:Request" -> "Request"
     */
    private static String extractTypeName(String signature) {
        if (signature == null || signature.isEmpty()) {
            return null;
        }

        // Handle qualified names (e.g., "ballerina/http:Request")
        int colonIndex = signature.lastIndexOf(':');
        if (colonIndex != -1) {
            return signature.substring(colonIndex + 1);
        }

        // Handle simple names
        return signature;
    }
}
