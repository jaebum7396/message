package trade.future.ml;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.IntVector;
import smile.regression.RandomForest;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class GeneticMLModel {
    private static final Logger logger = Logger.getLogger(GeneticMLModel.class.getName());
    private RandomForest bestModel;
    private final double priceChangeThreshold;
    private static final int MINIMUM_DATA_POINTS = 50;
    private double[] featureImportance;

    private static final int POPULATION_SIZE = 50;
    private static final int MAX_GENERATIONS = 20;
    private static final double MUTATION_RATE = 0.1;
    private static final double CROSSOVER_RATE = 0.8;

    public GeneticMLModel(double priceChangeThreshold) {
        this.priceChangeThreshold = priceChangeThreshold;
    }

    public void trainWithGeneticAlgorithm(BarSeries series, List<Indicator<Num>> indicators, int trainSize) {
        if (series.getBarCount() < MINIMUM_DATA_POINTS) {
            logger.warning("훈련에 필요한 데이터 포인트가 부족합니다.");
            return;
        }

        double[][] X = prepareFeatures(series, indicators, trainSize);
        int[] y = prepareLabels(series, trainSize);

        List<Individual> population = initializePopulation(indicators.size());

        for (int generation = 0; generation < MAX_GENERATIONS; generation++) {
            evaluatePopulation(population, X, y);
            population.sort(Comparator.comparingDouble(i -> -i.fitness)); // 적합도 내림차순 정렬

            List<Individual> newPopulation = new ArrayList<>();
            while (newPopulation.size() < POPULATION_SIZE) {
                Individual parent1 = tournamentSelection(population);
                Individual parent2 = tournamentSelection(population);

                if (Math.random() < CROSSOVER_RATE) {
                    List<Individual> children = crossover(parent1, parent2);
                    newPopulation.addAll(children);
                } else {
                    newPopulation.add(parent1);
                    newPopulation.add(parent2);
                }
            }

            mutatePopulation(newPopulation);
            population = newPopulation;

            logger.info("세대 " + generation + " 완료. 최고 적합도: " + population.get(0).fitness);
        }

        Individual bestIndividual = population.get(0);
        trainFinalModel(X, y, bestIndividual);
    }

    private double[][] prepareFeatures(BarSeries series, List<Indicator<Num>> indicators, int trainSize) {
        double[][] X = new double[trainSize][indicators.size()];
        for (int i = 0; i < trainSize; i++) {
            for (int j = 0; j < indicators.size(); j++) {
                X[i][j] = indicators.get(j).getValue(i).doubleValue();
            }
        }
        return X;
    }

    private int[] prepareLabels(BarSeries series, int trainSize) {
        int[] y = new int[trainSize];
        for (int i = 0; i < trainSize - 1; i++) {
            double currentPrice = series.getBar(i).getClosePrice().doubleValue();
            double nextPrice = series.getBar(i + 1).getClosePrice().doubleValue();
            double priceChangePercent = (nextPrice - currentPrice) / currentPrice * 100;

            if (priceChangePercent > priceChangeThreshold) y[i] = 1;
            else if (priceChangePercent < -priceChangeThreshold) y[i] = -1;
            else y[i] = 0;
        }
        y[trainSize - 1] = 0; // 마지막 데이터 포인트
        return y;
    }

    private List<Individual> initializePopulation(int numFeatures) {
        List<Individual> population = new ArrayList<>();
        for (int i = 0; i < POPULATION_SIZE; i++) {
            population.add(new Individual(numFeatures));
        }
        return population;
    }

    private void evaluatePopulation(List<Individual> population, double[][] X, int[] y) {
        for (Individual individual : population) {
            double[][] selectedX = selectFeatures(X, individual.genes);
            RandomForest model = trainModel(selectedX, y, individual.hyperparameters);
            individual.fitness = evaluateModel(model, selectedX, y);
        }
    }

    private Individual tournamentSelection(List<Individual> population) {
        int tournamentSize = 5;
        Individual best = null;
        for (int i = 0; i < tournamentSize; i++) {
            Individual contestant = population.get((int) (Math.random() * population.size()));
            if (best == null || contestant.fitness > best.fitness) {
                best = contestant;
            }
        }
        return best;
    }

    private List<Individual> crossover(Individual parent1, Individual parent2) {
        Individual child1 = new Individual(parent1.genes.length);
        Individual child2 = new Individual(parent1.genes.length);

        int crossoverPoint = (int) (Math.random() * parent1.genes.length);

        for (int i = 0; i < parent1.genes.length; i++) {
            if (i < crossoverPoint) {
                child1.genes[i] = parent1.genes[i];
                child2.genes[i] = parent2.genes[i];
            } else {
                child1.genes[i] = parent2.genes[i];
                child2.genes[i] = parent1.genes[i];
            }
        }

        // Hyperparameters crossover
        child1.hyperparameters = new Properties();
        child2.hyperparameters = new Properties();
        child1.hyperparameters.putAll(parent1.hyperparameters);
        child2.hyperparameters.putAll(parent2.hyperparameters);

        return Arrays.asList(child1, child2);
    }

    private void mutatePopulation(List<Individual> population) {
        for (Individual individual : population) {
            for (int i = 0; i < individual.genes.length; i++) {
                if (Math.random() < MUTATION_RATE) {
                    individual.genes[i] = !individual.genes[i];
                }
            }

            // Mutate hyperparameters
            if (Math.random() < MUTATION_RATE) {
                individual.hyperparameters.setProperty("smile.random.forest.trees",
                        String.valueOf((int) (Math.random() * 100) + 50));
            }
            if (Math.random() < MUTATION_RATE) {
                individual.hyperparameters.setProperty("smile.random.forest.max.depth",
                        String.valueOf((int) (Math.random() * 10) + 5));
            }
        }
    }

    private void trainFinalModel(double[][] X, int[] y, Individual bestIndividual) {
        double[][] selectedX = selectFeatures(X, bestIndividual.genes);
        DataFrame df = createDataFrame(selectedX);
        df = df.merge(IntVector.of("y", y));

        Formula formula = Formula.lhs("y");
        this.bestModel = RandomForest.fit(formula, df, bestIndividual.hyperparameters);
        this.featureImportance = bestModel.importance();
    }


    private double[][] selectFeatures(double[][] X, boolean[] genes) {
        List<Integer> selectedFeatures = new ArrayList<>();
        for (int i = 0; i < genes.length; i++) {
            if (genes[i]) {
                selectedFeatures.add(i);
            }
        }

        double[][] selectedX = new double[X.length][selectedFeatures.size()];
        for (int i = 0; i < X.length; i++) {
            for (int j = 0; j < selectedFeatures.size(); j++) {
                selectedX[i][j] = X[i][selectedFeatures.get(j)];
            }
        }
        return selectedX;
    }

    private RandomForest trainModel(double[][] X, int[] y, Properties hyperparameters) {
        DataFrame df = createDataFrame(X);
        df = df.merge(IntVector.of("y", y));

        Formula formula = Formula.lhs("y");
        return RandomForest.fit(formula, df, hyperparameters);
    }


    private double evaluateModel(RandomForest model, double[][] X, int[] y) {
        DataFrame df = createDataFrame(X);
        int[] predictions = Arrays.stream(model.predict(df))
                .mapToInt(d -> (int) Math.round(d))
                .toArray();
        int correct = 0;
        for (int i = 0; i < y.length; i++) {
            if (predictions[i] == y[i]) {
                correct++;
            }
        }
        return (double) correct / y.length;
    }

    public int predict(List<Indicator<Num>> indicators, int index) {
        double[] features = new double[indicators.size()];
        for (int i = 0; i < indicators.size(); i++) {
            features[i] = indicators.get(i).getValue(index).doubleValue();
        }

        DataFrame df = createDataFrame(new double[][]{features});
        double prediction = bestModel.predict(df)[0];
        if (prediction > 0.5) {
            return 1;  // 상승
        } else if (prediction < -0.5) {
            return -1;  // 하락
        } else {
            return 0;  // 유지
        }
    }

    private DataFrame createDataFrame(double[][] X) {
        String[] featureNames = IntStream.range(0, X[0].length)
                .mapToObj(i -> "feature" + i)
                .toArray(String[]::new);
        DataFrame df = DataFrame.of(X, featureNames);
        return df;
    }

    private class Individual {
        boolean[] genes;
        Properties hyperparameters;
        double fitness;

        public Individual(int numFeatures) {
            this.genes = new boolean[numFeatures];
            for (int i = 0; i < numFeatures; i++) {
                this.genes[i] = Math.random() < 0.5;
            }

            this.hyperparameters = new Properties();
            this.hyperparameters.setProperty("smile.random.forest.trees",
                    String.valueOf((int) (Math.random() * 100) + 50));
            this.hyperparameters.setProperty("smile.random.forest.max.depth",
                    String.valueOf((int) (Math.random() * 10) + 5));

            this.fitness = 0.0;
        }
    }
}