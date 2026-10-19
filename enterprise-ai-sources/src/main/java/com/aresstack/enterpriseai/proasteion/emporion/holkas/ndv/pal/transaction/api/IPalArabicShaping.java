package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api;

/**
 * Schnittstelle für Arabic/BiDi-Shaping.
 */
public interface IPalArabicShaping {

    String unshape(String text);

    String shapeIBM420(String text);
}

