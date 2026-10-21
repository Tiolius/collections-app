package app;

import java.util.Map;
import java.util.Set;

/**
 * Тип коллекции: название, поля объектов (ключи из Repo.FIELDS), сколько коллекций этого типа,
 * цвета строк объектов по статусу (статус → цвет #rrggbb).
 */
public record Kind(long id, String name, Set<String> fields, int used, Map<String, String> statusColors) {
}
