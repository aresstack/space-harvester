package com.aresstack.spaceharvester.application.passages;

import java.util.List;

/**
 * Cosinus und Mittelwert für die Fenster der Passagenbildung, wie {@code VectorMath} im Enterprise AI Client (aus askai
 * arch): Mittelwerte in {@code double} summiert, damit sich keine Grenze verschiebt; verschiedene Dimensionen sind ein
 * harter Fehler, nie Rauschen. Alle Vektoren einer Zerlegung stammen von einem Modell.
 */
final class VectorMath {

    private VectorMath() {
    }

    /** Cosinus zweier Mittelwerte aus {@link #meanValues}; Norm 0 ergibt 0. */
    static double cosine(double[] left, double[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("Dimensionen verschieden: " + left.length + " vs " + right.length);
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int d = 0; d < left.length; d++) {
            dot += left[d] * right[d];
            leftNorm += left[d] * left[d];
            rightNorm += right[d] * right[d];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    /** Das arithmetische Mittel als {@code double}-Werte; verlangt eine Dimension für alle. */
    static double[] meanValues(List<float[]> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            throw new IllegalArgumentException("cannot average zero vectors");
        }
        double[] sum = new double[vectors.get(0).length];
        for (float[] vector : vectors) {
            if (vector.length != sum.length) {
                throw new IllegalArgumentException("Dimensionen verschieden: " + sum.length + " vs " + vector.length);
            }
            for (int d = 0; d < sum.length; d++) {
                sum[d] += vector[d];
            }
        }
        for (int d = 0; d < sum.length; d++) {
            sum[d] /= vectors.size();
        }
        return sum;
    }
}
