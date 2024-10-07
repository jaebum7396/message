package trade.future.ml;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;

import java.util.*;
import java.util.logging.Logger;

public class MLGeneticModel extends MLModel {
    private static final Logger logger = Logger.getLogger(MLGeneticModel.class.getName());
    private static final int POPULATION_SIZE = 100;
    private static final int MAX_GENERATIONS = 100;
    private static final double MUTATION_RATE = 0.1;
    private static final double CROSSOVER_RATE = 0.7;

    private List<Individual> population;
    private Random random;

    public MLGeneticModel(List<Indicator<Num>> indicators) {
        super(indicators);
        this.random = new Random();
    }

    public MLGeneticModel(double priceChangeThreshold, List<Indicator<Num>> indicators) {
        super(priceChangeThreshold, indicators);
        this.random = new Random();
    }

    @Override
    public void train(BarSeries series, int trainSize) {
        initializePopulation();
        for (int generation = 0; generation < MAX_GENERATIONS; generation++) {
            evaluatePopulation(series, trainSize);
            List<Individual> newPopulation = new ArrayList<>();
            while (newPopulation.size() < POPULATION_SIZE) {
                Individual parent1 = selectParent();
                Individual parent2 = selectParent();
                if (random.nextDouble() < CROSSOVER_RATE) {
                    Individual[] children = crossover(parent1, parent2);
                    newPopulation.add(mutate(children[0]));
                    if (newPopulation.size() < POPULATION_SIZE) {
                        newPopulation.add(mutate(children[1]));
                    }
                } else {
                    newPopulation.add(mutate(new Individual(parent1)));
                    if (newPopulation.size() < POPULATION_SIZE) {
                        newPopulation.add(mutate(new Individual(parent2)));
                    }
                }
            }
            population = newPopulation;
            logger.info("Generation " + generation + " completed");
        }
        logger.info("Genetic algorithm training completed");
    }

    @Override
    public int predict(List<Indicator<Num>> indicators, int index) {
        Individual bestIndividual = getBestIndividual();
        double prediction = 0;
        for (int i = 0; i < indicators.size(); i++) {
            prediction += bestIndividual.weights[i] * indicators.get(i).getValue(index).doubleValue();
        }
        if (prediction > bestIndividual.buyThreshold) return 1;
        if (prediction < bestIndividual.sellThreshold) return -1;
        return 0;
    }

    @Override
    public double[] predictProbabilities(List<Indicator<Num>> indicators, int index) {
        int prediction = predict(indicators, index);
        double[] probabilities = new double[3];
        if (prediction == 1) probabilities[2] = 1.0;
        else if (prediction == -1) probabilities[0] = 1.0;
        else probabilities[1] = 1.0;
        return probabilities;
    }

    private void initializePopulation() {
        population = new ArrayList<>();
        for (int i = 0; i < POPULATION_SIZE; i++) {
            population.add(new Individual(indicators.size()));
        }
    }

    private void evaluatePopulation(BarSeries series, int trainSize) {
        for (Individual individual : population) {
            individual.fitness = calculateFitness(individual, series, trainSize);
        }
        population.sort(Comparator.comparingDouble(i -> -i.fitness));  // Descending order
    }

    private double calculateFitness(Individual individual, BarSeries series, int trainSize) {
        double totalReturn = 1.0;
        boolean inPosition = false;
        double entryPrice = 0;

        for (int i = 0; i < trainSize - 1; i++) {
            double prediction = 0;
            for (int j = 0; j < indicators.size(); j++) {
                prediction += individual.weights[j] * indicators.get(j).getValue(i).doubleValue();
            }

            if (!inPosition && prediction > individual.buyThreshold) {
                inPosition = true;
                entryPrice = series.getBar(i).getClosePrice().doubleValue();
            } else if (inPosition && prediction < individual.sellThreshold) {
                inPosition = false;
                double exitPrice = series.getBar(i).getClosePrice().doubleValue();
                totalReturn *= (exitPrice / entryPrice);
            }
        }

        return totalReturn;
    }

    private Individual selectParent() {
        double totalFitness = population.stream().mapToDouble(i -> i.fitness).sum();
        double randomPoint = random.nextDouble() * totalFitness;
        double sum = 0;
        for (Individual individual : population) {
            sum += individual.fitness;
            if (sum >= randomPoint) {
                return individual;
            }
        }
        return population.get(population.size() - 1);  // Fallback
    }

    private Individual[] crossover(Individual parent1, Individual parent2) {
        int crossoverPoint = random.nextInt(indicators.size());
        Individual child1 = new Individual(indicators.size());
        Individual child2 = new Individual(indicators.size());

        for (int i = 0; i < indicators.size(); i++) {
            if (i < crossoverPoint) {
                child1.weights[i] = parent1.weights[i];
                child2.weights[i] = parent2.weights[i];
            } else {
                child1.weights[i] = parent2.weights[i];
                child2.weights[i] = parent1.weights[i];
            }
        }

        child1.buyThreshold = (parent1.buyThreshold + parent2.buyThreshold) / 2;
        child1.sellThreshold = (parent1.sellThreshold + parent2.sellThreshold) / 2;
        child2.buyThreshold = (parent1.buyThreshold + parent2.buyThreshold) / 2;
        child2.sellThreshold = (parent1.sellThreshold + parent2.sellThreshold) / 2;

        return new Individual[]{child1, child2};
    }

    private Individual mutate(Individual individual) {
        for (int i = 0; i < individual.weights.length; i++) {
            if (random.nextDouble() < MUTATION_RATE) {
                individual.weights[i] += random.nextGaussian() * 0.1;  // Small random change
            }
        }
        if (random.nextDouble() < MUTATION_RATE) {
            individual.buyThreshold += random.nextGaussian() * 0.1;
        }
        if (random.nextDouble() < MUTATION_RATE) {
            individual.sellThreshold += random.nextGaussian() * 0.1;
        }
        return individual;
    }

    private Individual getBestIndividual() {
        return population.get(0);  // Assuming population is sorted
    }

    private class Individual {
        double[] weights;
        double buyThreshold;
        double sellThreshold;
        double fitness;

        Individual(int size) {
            weights = new double[size];
            for (int i = 0; i < size; i++) {
                weights[i] = random.nextGaussian();
            }
            buyThreshold = random.nextDouble();
            sellThreshold = random.nextDouble() - 1;  // Ensure sell threshold is lower than buy threshold
        }

        Individual(Individual other) {
            this.weights = Arrays.copyOf(other.weights, other.weights.length);
            this.buyThreshold = other.buyThreshold;
            this.sellThreshold = other.sellThreshold;
            this.fitness = other.fitness;
        }
    }
}
