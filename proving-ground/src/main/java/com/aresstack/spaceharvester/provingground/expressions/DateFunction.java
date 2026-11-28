package com.aresstack.spaceharvester.provingground.expressions;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * {@code {{Date('dd.MM.yyyy')}}}: now in the given {@link SimpleDateFormat} pattern; without a pattern the epoch
 * milliseconds (WD4J built-in).
 */
public final class DateFunction implements ExpressionFunction {

    private static final FunctionMetadata META = new FunctionMetadata("Date",
            "Aktuelles Datum/Uhrzeit, optional mit Muster (SimpleDateFormat)", List.of("pattern?"), null);

    @Override
    public String invoke(List<String> args, ResolutionContext context) throws FunctionExecutionException {
        Date now = new Date();
        if (!args.isEmpty() && args.get(0) != null && !args.get(0).isEmpty()) {
            try {
                return new SimpleDateFormat(args.get(0)).format(now);
            } catch (IllegalArgumentException invalid) {
                throw new FunctionExecutionException("Ungültiges Datumsmuster: " + args.get(0), invalid);
            }
        }
        return Long.toString(now.getTime());
    }

    @Override
    public FunctionMetadata metadata() {
        return META;
    }
}
