/**
 * Asynchrone Aufträge (Ernte, Synchronisation, Export) mit gemeinsamem Status-, Fortschritts- und Abbruchmechanismus:
 * {@link com.aresstack.spaceharvester.application.jobs.Jobs} nimmt Aufträge an, begrenzt ihre Parallelität und hält
 * ihren Zustand über den Port {@link com.aresstack.spaceharvester.application.jobs.JobStore} fest. Quellenneutral: ein
 * Auftrag ist eine {@link com.aresstack.spaceharvester.application.jobs.JobTask}, die nur ihren
 * {@link com.aresstack.spaceharvester.application.jobs.JobContext} kennt.
 */
package com.aresstack.spaceharvester.application.jobs;
