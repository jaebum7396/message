package trade.future.ml;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SignalProximityScanner {
    private List<Indicator<Num>> indicators;
    private BaseBarSeries series;
    private MLModel model;
    private double threshold;
    private double proximityThreshold;

    public SignalProximityScanner(List<Indicator<Num>> indicators,
                                  BaseBarSeries series,
                                  MLModel model,
                                  double threshold,
                                  double proximityThreshold) {
        this.indicators = indicators;
        this.series = series;
        this.model = model;
        this.threshold = threshold;
        this.proximityThreshold = proximityThreshold;
    }

    public boolean isNearSignal() {
        boolean isNearSignal = false;
        List<String> potentialSymbols = new ArrayList<>();

        BarSeries series = this.series;
        List<Indicator<Num>> indicators = this.indicators;

        int currentIndex = series.getEndIndex();
        double[] probabilities = model.predictProbabilities(indicators, currentIndex);

        if (isNearThreshold(probabilities[2]) || isNearThreshold(probabilities[0])) {
            isNearSignal = true;
        }

        return isNearSignal;
    }

    private boolean isNearThreshold(double probability) {
        return Math.abs(probability - threshold) <= proximityThreshold;
    }

    public boolean isLikelyToMove() {
        int currentIndex = series.getEndIndex();
        double[] probabilities = model.predictProbabilities(indicators, currentIndex);

        // probabilities[1]은 횡보(중립) 확률
        return probabilities[1] > 0.4;
    }

    public void printSignalProximity(String symbol) {
        BarSeries series = this.series;
        List<Indicator<Num>> indicators = this.indicators;
        int currentIndex = series.getEndIndex();
        double[] probabilities = model.predictProbabilities(indicators, currentIndex);

        String[] headers = {"Symbol", "Current Price", "Long Proximity", "Short Proximity", "Up Prob", "Neutral Prob", "Down Prob"};
        String[] values = {
                symbol,
                String.format("%.2f", series.getLastBar().getClosePrice().doubleValue()),
                String.format("%.4f", probabilities[2] - threshold),
                String.format("%.4f", probabilities[0] - threshold),
                String.format("%.4f", probabilities[2]),
                String.format("%.4f", probabilities[1]),
                String.format("%.4f", probabilities[0])
        };

        List<String[]> rows = new ArrayList<>();
        rows.add(headers);
        rows.add(values);

        printGrid(rows);
    }

    private void printGrid(List<String[]> rows) {
        int[] columnWidths = calculateColumnWidths(rows);
        String horizontalLine = createHorizontalLine(columnWidths);

        System.out.println(horizontalLine);
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            for (int j = 0; j < row.length; j++) {
                System.out.printf("| %-" + (columnWidths[j] - 1) + "s", row[j]);
            }
            System.out.println("|");
            if (i == 0) {  // 헤더 다음에 구분선 추가
                System.out.println(horizontalLine);
            }
        }
        System.out.println(horizontalLine);
    }

    private int[] calculateColumnWidths(List<String[]> rows) {
        int[] widths = new int[rows.get(0).length];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i].length() + 2);  // +2 for padding
            }
        }
        return widths;
    }

    private String createHorizontalLine(int[] columnWidths) {
        StringBuilder sb = new StringBuilder("+");
        for (int width : columnWidths) {
            sb.append("-".repeat(width)).append("+");
        }
        return sb.toString();
    }
}