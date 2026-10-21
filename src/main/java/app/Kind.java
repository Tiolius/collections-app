package app;

import java.util.Set;

/** Тип коллекции: название, поля объектов (ключи из Repo.FIELDS), сколько коллекций этого типа. */
public record Kind(long id, String name, Set<String> fields, int used) {
}
