package com.clairvoyant.sqlserver_hive_compare;

import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.hive.HiveContext;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.sql.*;
import org.apache.spark.SparkConf;
import org.apache.commons.lang.ArrayUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.*;

import static org.apache.spark.sql.functions.callUDF;
import static org.apache.spark.sql.functions.concat;
import static org.apache.spark.sql.functions.lit;

public class SqlServerHiveCompare {

    public static void main(String[] args) throws Exception {

        String sqlServerUrl;
        String sqlServerUsername;
        String sqlServerPassword;
        String sqlServerDriver;
        String sqlServerPort;

        CommandLineArguments arguments = new CommandLineArguments(args);

        String sqlDatabase = arguments.getsqlDatabase();
        String sqlTable = arguments.getsqlTable();
        String hiveDatabase = arguments.gethiveDatabase();
        String hiveTableName = arguments.gethiveTable();
        String whereClause = arguments.getwhereClause();
        String excludeColumns[] = arguments.getexcludeColumns();

        // Getting Timestamp
        SimpleDateFormat formatter = new SimpleDateFormat("ddMMyyyyHHmmss");
        Date date = new Date();
        String timestamp = formatter.format(date);

        //getting beans from spring context
        AbstractApplicationContext context = new AnnotationConfigApplicationContext(AppConfig.class);

        //getting JavaSpark context
        JavaSparkContext javaSparkContext = (JavaSparkContext) context.getBean("sc");

        //getting HIveContext
        HiveContext hiveContext = (HiveContext) context.getBean("hiveContext");

        //getting SparkConf
        SparkConf sparkConf = (SparkConf) context.getBean("sparkConf");

        // Masking the info Logs
        javaSparkContext.setLogLevel("WARN");

        sparkConf.setAppName("SqlServerHiveCompare");

        //load SqlServer properties
        sqlServerUrl = (String) context.getBean("sqlServerUrl");
        sqlServerUsername = (String) context.getBean("sqlServerUsername");
        sqlServerPassword = (String) context.getBean("sqlServerPassword");
        sqlServerDriver = (String) context.getBean("sqlServerDriver");
        sqlServerPort = (String) context.getBean("sqlServerPort");

        // Get the schema from the table in sql server
        Map<String, String> sqlSchemaOptions = new HashMap<>();
        sqlSchemaOptions.put("url", sqlServerUrl + ":" + sqlServerPort + ";user=" + sqlServerUsername + ";password=" + sqlServerPassword + ";databaseName=" + sqlDatabase);
        sqlSchemaOptions.put("dbtable", "(select DATA_TYPE,COLUMN_NAME from INFORMATION_SCHEMA.COLUMNS where TABLE_NAME='" + sqlTable + "') select_telarix");
        sqlSchemaOptions.put("driver", sqlServerDriver);

        DataFrame sqlServerTableSchema = hiveContext.read().format("jdbc").options(sqlSchemaOptions).load();

        // Adding Where clause
        String sqlQuery = "(SELECT * FROM "+sqlTable;
        String hiveQuery = "SELECT * FROM " + hiveDatabase + "." + hiveTableName;
        if (whereClause != null) {
            sqlQuery += " where (" + whereClause + ")";
            hiveQuery += " where (" + whereClause + ")";
        }

        // Get the data from table in Sql Server
        Map<String, String> sqlQueryOptions = new HashMap<>();
        sqlQueryOptions.put("url", sqlServerUrl + ":" + sqlServerPort + ";user=" + sqlServerUsername + ";password=" + sqlServerPassword + ";databaseName=" + sqlDatabase);
        sqlQueryOptions.put("dbtable", sqlQuery + ") select_telarix");
        sqlQueryOptions.put("driver", sqlServerDriver);
        DataFrame sqlServerTable = hiveContext.read().format("jdbc").options(sqlQueryOptions).load();

        // Get the data from Hive Table
        DataFrame hiveTable = hiveContext.sql(hiveQuery);

        // Casting the columns in dataframe where sqlserver table is stored
        DataFrame columnsCastedSqlServerTable = castColumns(sqlServerTableSchema, sqlServerTable);
        columnsCastedSqlServerTable.registerTempTable("sql_table");

        // Excluding Columns as requested by the User
        if (excludeColumns != null) {
            for (String column : excludeColumns) {
                columnsCastedSqlServerTable = columnsCastedSqlServerTable.drop(column);
                columnsCastedSqlServerTable.show();
                hiveTable = hiveTable.drop(column.toLowerCase());
                hiveTable.show();
            }
        }

        String[] sqlTableFields = columnsCastedSqlServerTable.schema().fieldNames();
        String[] hiveTableFields = hiveTable.schema().fieldNames();

        ArrayList<String> commonColumnsSql = new ArrayList<>();
        ArrayList<String> commonColumnsHive = new ArrayList<>();

        // Getting Common Columns from Both SqlServer and Hive
        for (String column : sqlTableFields) {
            if (ArrayUtils.contains(hiveTableFields, column.toLowerCase())) {
                commonColumnsHive.add(column.toLowerCase());
                commonColumnsSql.add(column);
            }
        }

        // Creating final Hive Table by reordering columns
        StringBuilder matchedColumnsSql = new StringBuilder();
        StringBuilder matchedColumnsHive = new StringBuilder();
        for (String s : commonColumnsSql) {

            matchedColumnsSql.append(s);
            matchedColumnsSql.append(",");
            matchedColumnsHive.append(s.toLowerCase());
            matchedColumnsHive.append(",");
        }
        matchedColumnsSql.setLength(matchedColumnsSql.length() - 1);
        matchedColumnsHive.setLength(matchedColumnsHive.length() - 1);

        // Sql Table with Matched Columns
        columnsCastedSqlServerTable = hiveContext.sql("select " + matchedColumnsSql.toString() + " from sql_table");
        columnsCastedSqlServerTable.registerTempTable("sql_table");

        // Hive Table with Matched Columns
        DataFrame hiveTableSorted = hiveContext.sql("SELECT " + matchedColumnsHive.toString() + " FROM " + hiveDatabase + "." + hiveTableName);
        hiveTableSorted.registerTempTable("hive_table");

        String[] sqlColumns = columnsCastedSqlServerTable.columns();
        StringBuilder columnsForFinalTableDisplay = new StringBuilder();
        for (String s : sqlColumns) {
            hiveTableSorted = hiveTableSorted.withColumnRenamed(s.toLowerCase(), s + "_hive");
            String hiveColName = s + "_hive";
            columnsForFinalTableDisplay.append(s);
            columnsForFinalTableDisplay.append(",");
            columnsForFinalTableDisplay.append(hiveColName);
            columnsForFinalTableDisplay.append(",");
        }
        columnsForFinalTableDisplay.setLength(columnsForFinalTableDisplay.length() - 1);

        System.out.println("sql: " + columnsCastedSqlServerTable.count());
        System.out.println("Hive: " + hiveTableSorted.count());

        try {
            // Columns in Sql but not in hive
            DataFrame dataInSqlButNotHive = columnsCastedSqlServerTable.except(hiveTableSorted);
            DataFrame dataInHiveButNotSql = hiveTableSorted.except(columnsCastedSqlServerTable);
            if (dataInSqlButNotHive.count() == 0 && dataInHiveButNotSql.count() == 0) {
                System.out.println("==============================================================");
                System.out.println("Tables are equal");
                System.out.println("==============================================================");
            } else {

//                DataFrame dataInSqlButNotHiveWithOutDuplicates = dataInSqlButNotHive.dropDuplicates();
//                DataFrame dataInHiveButNotSqlWithOutDuplicates = dataInHiveButNotSql.dropDuplicates();
//
//                DataFrame dataInSqlButNotHiveDuplicates = dataInSqlButNotHive.except(dataInSqlButNotHiveWithOutDuplicates);
//                DataFrame dataInHiveButNotSqlDuplicates = dataInHiveButNotSql.except(dataInHiveButNotSqlWithOutDuplicates);

                // Unmatched Data both in Sql and Hive
                DataFrame unmatchedDataInBothSqlAndHive = columnsCastedSqlServerTable.unionAll(hiveTableSorted).except(columnsCastedSqlServerTable.intersect(hiveTableSorted));

                String columns[] = unmatchedDataInBothSqlAndHive.columns();
                List<String> fullColumnNotMatching = new ArrayList<>();

                for (String s : columns) {
                    // Checking if the whole column is Different
                    if (sqlServerTable.count() == unmatchedDataInBothSqlAndHive.count() / 2) {
                        fullColumnNotMatching.add(s);
                    }
                }

                // Getting Cartesian Product
                columnsCastedSqlServerTable = columnsCastedSqlServerTable.withColumn("index", functions.monotonically_increasing_id());
                DataFrame cartesianProduct = columnsCastedSqlServerTable.join(hiveTableSorted);
                cartesianProduct.registerTempTable("just_test");
                StringBuilder concatenatedColumnNames = new StringBuilder();

                // Column Comparision
                for (String s : sqlColumns) {
                    Column sqlCol = cartesianProduct.col(s);
                    Column hiveCol = cartesianProduct.col(s + "_hive");

                    Column concatCol = concat(sqlCol, lit("_"), hiveCol);
                    cartesianProduct = cartesianProduct.withColumn(s + "concat_col", concatCol);

                    String columnName = s + "concat_col";
//                    Column concatColumn = cartesianProduct.col(columnName);
                    cartesianProduct = cartesianProduct.withColumn(columnName, callUDF("columnsCompare", sqlCol.cast("String"), hiveCol.cast("String")));
                    concatenatedColumnNames.append(columnName);
                    concatenatedColumnNames.append("+");
                }
                concatenatedColumnNames.setLength(concatenatedColumnNames.length() - 1);

                cartesianProduct.registerTempTable("cartesian_product");
                DataFrame concatenatedColumnsInCartesianProduct = hiveContext.sql("select *," + concatenatedColumnNames.toString() + " as total  from cartesian_product");
                concatenatedColumnsInCartesianProduct.registerTempTable("final_results");
                hiveContext.sql("select " + columnsForFinalTableDisplay.toString() + ",total from final_results").registerTempTable("final");
                hiveContext.sql("select index,max(total) as max_total from final_results group by index").registerTempTable("final_grouped");
                DataFrame finalResults = hiveContext.sql("SELECT * FROM final LEFT OUTER JOIN final_grouped ON final.total = final_grouped.max_total WHERE final_grouped.max_total IS NOT NULL and final_grouped.max_total<>" + sqlTableFields.length + " order by index");
                finalResults.registerTempTable("final_table");
                finalResults = hiveContext.sql("select index," + columnsForFinalTableDisplay.toString() + " from final_table");
                String columnsOfFinalResults[] = finalResults.columns();


                // Building Html Table
                StringBuilder htmlStringBuilder = new StringBuilder();

                htmlStringBuilder.append("<h3 class=\"gray\"> Mismatched Data of ").append(sqlTable).append(" in SqlServer and ").append(hiveTableName).append(" in Hive </h3>");

                htmlStringBuilder.append("<html><head><style>table {font-family: arial, sans-serif;border-collapse:collapse;width: 100%;}td, th {border: 1px solid #dddddd;text-align: left;padding: 8px;}</style></head><body><table>");

                htmlStringBuilder.append("<tr>");
                for (String s : columnsOfFinalResults) {
                    htmlStringBuilder.append("<th>").append(s).append("</th>");
                }
                htmlStringBuilder.append("</tr>");

                finalResults = finalResults.withColumn("html_col", lit("<tr>"));
                finalResults = finalResults.withColumn("index", concat(lit("<td>"), finalResults.col("index"), lit("</td>")));
                finalResults = finalResults.withColumn("html_col", concat(finalResults.col("html_col"),finalResults.col("index")));

                for (int i = 1; i < (finalResults.columns().length) - 1; i = i + 2) {
                    Column a = finalResults.col(finalResults.columns()[i]);
                    Column b = finalResults.col(finalResults.columns()[i + 1]);
                    finalResults = finalResults.withColumn("html_col", concat(finalResults.col("html_col"), callUDF("htmlGenerator", a.cast("String"), b.cast("String"))));
                }
                finalResults = finalResults.withColumn("html_col", concat(finalResults.col("html_col"), lit("</tr>")));

                finalResults = finalResults.select("html_col");
                Row[] dataRows = finalResults.collect();

                for (Row row : dataRows) {
                    htmlStringBuilder.append(row.get(0));
                    htmlStringBuilder.append("\n");
                }

                if (fullColumnNotMatching.isEmpty()) {
                    htmlStringBuilder.append("<h3 class=\"gray\"> There are No Fully Unmatched Columns </h3>");
                } else {
                    // TODO:Add fully unmatched columns
                    System.out.println("");
                }

                htmlStringBuilder.append("</table></body></html>");

                System.out.println("==============================================================");
                System.out.println("use http://localhost:49090/" + sqlTable + timestamp + ".html  link to view the Differences");
                System.out.println("==============================================================");

                BufferedWriter writer = new BufferedWriter(new FileWriter("/home/ixadmin_ext/aravind/spark_code/sqlserver_hive_compare/html/" + sqlTable + timestamp + ".html"));
                writer.write(htmlStringBuilder.toString());
                writer.close();


            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
            System.out.println("==============================================================");
            System.out.println("Tables are not equal");
            System.out.println("==============================================================");
        }
    }

    // Casting Columns
    private static DataFrame castColumns(DataFrame tableForSchema, DataFrame table) {
        JavaRDD<Row> row_rdd = tableForSchema.javaRDD();
        List<Row> rows = row_rdd.collect();

        for (Row row : rows) {
            String col1 = row.getString(0);
            String col2 = row.getString(1);
            String map_col;

            switch (col1) {
                case "datetime":
                case "date":
                    map_col = "timestamp";
                    break;
                case "bit":
                    map_col = "Integer";
                    break;
                case "Money":
                    map_col = "decimal(19,4)";
                    break;
                case "decimal":
                    map_col = "decimal(19,6)";
                    break;
                default:
                    map_col = null;
                    break;
            }
            if (map_col != null) {
                table = table.withColumn(col2, table.col(col2).cast(map_col));
            }
        }
        return table;
    }
}
