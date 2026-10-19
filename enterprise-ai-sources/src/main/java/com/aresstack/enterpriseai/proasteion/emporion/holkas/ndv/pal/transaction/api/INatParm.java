package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.*;

import java.io.Serializable;

/**
 * Schnittstelle für Natural-Parameter.
 */
public interface INatParm extends Serializable {

    IReport getReport();

    ICharAssign getCharAssign();

    IFldApp getFldApp();

    ICompOpt getCompOpt();

    ILimit getLimit();

    IRegional getRegional();

    IRpc getRpc();

    IBuffSize getBuffSize();

    IErr getErr();

    IPalTypeNatParm[] get(int index);
}

