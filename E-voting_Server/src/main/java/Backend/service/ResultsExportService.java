package Backend.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Renders an election's results as a spreadsheet, for handing to a returning officer or
 * filing as a record after polling closes.
 *
 * <p>Reuses {@link ElectionResultsService}'s existing queries rather than duplicating them —
 * this is a second view of the same numbers the results page shows, not a separate source
 * of truth. Everything here is read-only: generating a workbook has no way to affect the
 * count it describes.
 */
@Service
public class ResultsExportService {

    private final ElectionResultsService results;

    public ResultsExportService(ElectionResultsService results) {
        this.results = results;
    }

    @Transactional(readOnly = true)
    public byte[] export(Integer electionId) throws IOException {
        Map<String, Object> summary = results.getResults(electionId);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = headerStyle(workbook);

            writeSummarySheet(workbook, headerStyle, summary);
            writePartyTotalsSheet(workbook, headerStyle, results.getPartyTotals(electionId));
            writeConstituencyLeadersSheet(workbook, headerStyle, results.getConstituencyLeaders(electionId));
            writeStateDetailSheet(workbook, headerStyle, electionId, summary);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    @SuppressWarnings("unchecked")
    private void writeSummarySheet(XSSFWorkbook workbook, CellStyle headerStyle, Map<String, Object> summary) {
        Sheet sheet = workbook.createSheet("Summary");
        Object electionObj = summary.get("election");
        Map<String, Object> election = electionObj instanceof Map ? (Map<String, Object>) electionObj : Map.of();

        int rowNum = 0;
        rowNum = writeRow(sheet, rowNum, headerStyle, "Election", str(election.get("name")));
        rowNum = writeRow(sheet, rowNum, headerStyle, "Type", str(election.get("type")));
        rowNum = writeRow(sheet, rowNum, headerStyle, "Generated", DateTimeFormatter.ISO_LOCAL_DATE_TIME
                .format(java.time.LocalDateTime.now()));
        rowNum++;
        rowNum = writeRow(sheet, rowNum, headerStyle, "Eligible voters", str(summary.get("eligibleVoters")));
        rowNum = writeRow(sheet, rowNum, headerStyle, "Voters who voted", str(summary.get("votersVoted")));
        rowNum = writeRow(sheet, rowNum, headerStyle, "Ballots recorded", str(summary.get("totalVotesCast")));
        rowNum = writeRow(sheet, rowNum, headerStyle, "Turnout %", str(summary.get("turnoutPercent")));
        rowNum = writeRow(sheet, rowNum, headerStyle, "NOTA votes", str(summary.get("notaVotes")));
        rowNum = writeRow(sheet, rowNum, headerStyle, "NOTA %", str(summary.get("notaPercent")));
        rowNum = writeRow(sheet, rowNum, headerStyle, "Candidates contesting", str(summary.get("candidatesContesting")));
        rowNum = writeRow(sheet, rowNum, headerStyle, "Constituencies reporting",
                str(summary.get("constituenciesReporting")) + " of " + str(summary.get("constituenciesWithCandidates")));

        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 40 * 256);
    }

    private void writePartyTotalsSheet(XSSFWorkbook workbook, CellStyle headerStyle,
                                      List<Map<String, Object>> parties) {
        Sheet sheet = workbook.createSheet("Party Totals");
        writeHeaderRow(sheet, headerStyle, "Party", "Votes");
        int rowNum = 1;
        for (Map<String, Object> party : parties) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(str(party.get("party")));
            row.createCell(1).setCellValue(str(party.get("votes")));
        }
        autoSizeColumns(sheet, 2);
    }

    private void writeConstituencyLeadersSheet(XSSFWorkbook workbook, CellStyle headerStyle,
                                              List<Map<String, Object>> leaders) {
        Sheet sheet = workbook.createSheet("Constituency Leaders");
        writeHeaderRow(sheet, headerStyle, "Constituency", "District", "Leading candidate", "Party", "Votes", "Margin");
        int rowNum = 1;
        for (Map<String, Object> leader : leaders) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(str(leader.get("constituency_name")));
            row.createCell(1).setCellValue(str(leader.get("district")));
            row.createCell(2).setCellValue(str(leader.get("candidate_name")));
            row.createCell(3).setCellValue(str(leader.get("party")));
            row.createCell(4).setCellValue(str(leader.get("votes")));
            row.createCell(5).setCellValue(str(leader.get("margin")));
        }
        autoSizeColumns(sheet, 6);
    }

    @SuppressWarnings("unchecked")
    private void writeStateDetailSheet(XSSFWorkbook workbook, CellStyle headerStyle,
                                      Integer electionId, Map<String, Object> summary) {
        Sheet sheet = workbook.createSheet("State-wise Detail");
        writeHeaderRow(sheet, headerStyle, "State", "District", "Constituency", "Candidate", "Party", "Votes");

        Object electionObj = summary.get("election");
        String type = electionObj instanceof Map<?, ?> election && election.get("type") instanceof String t
                ? t : "CM";

        int rowNum = 1;
        for (Map<String, Object> state : results.getStatesWithVotes(electionId)) {
            Integer stateId = (Integer) state.get("id");
            String stateName = str(state.get("name"));
            List<Map<String, Object>> rows = "PM".equals(type)
                    ? results.getPmStateResults(electionId, stateId)
                    : results.getCmStateResults(electionId, stateId);
            for (Map<String, Object> item : rows) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(stateName);
                row.createCell(1).setCellValue(str(item.getOrDefault("district_name", item.get("district"))));
                row.createCell(2).setCellValue(str(item.get("constituency_name")));
                row.createCell(3).setCellValue(str(item.get("candidate_name")));
                row.createCell(4).setCellValue(str(item.get("party")));
                row.createCell(5).setCellValue(str(item.get("votes")));
            }
        }
        autoSizeColumns(sheet, 6);
    }

    private int writeRow(Sheet sheet, int rowNum, CellStyle labelStyle, String label, String value) {
        Row row = sheet.createRow(rowNum);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(labelStyle);
        row.createCell(1).setCellValue(value);
        return rowNum + 1;
    }

    private void writeHeaderRow(Sheet sheet, CellStyle headerStyle, String... columns) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < columns.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(columns[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private void autoSizeColumns(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
