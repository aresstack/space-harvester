/**
 * Ausgabe-Converter (#30): Schnittstelle, über die ein Export aus nativen Seiten und separatem ID-Baum zusätzliche
 * Formate erzeugt. Converter stehen ganz am Ende der Pipeline, lesen nur, was der Export ihnen aus dem Cache reicht,
 * und verändern nie Quelldaten; die Implementierungen sind Adapter in {@code output.convert}.
 */
package com.aresstack.spaceharvester.application.convert;
