package ru.lct.heat.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.config.HeatProperties.DnRange;
import ru.lct.heat.config.HeatProperties.Pipe;
import ru.lct.heat.config.HeatProperties.Restriction;

/** Числовые правила задачи поверх конфига: таблица ДУ, стоимость камер, отступы, показатель S. */
public final class Rules {

    private final HeatProperties props;
    private final List<Pipe> pipes;

    public Rules(HeatProperties props) {
        this.props = props;
        this.pipes = new ArrayList<>(props.getPipes());
        this.pipes.sort(Comparator.comparingInt(Pipe::getDn));
        if (pipes.isEmpty()) {
            throw new IllegalStateException("heat.pipes пуст: нет таблицы ДУ");
        }
    }

    public HeatProperties props() {
        return props;
    }

    public List<Pipe> pipes() {
        return pipes;
    }

    public Pipe pipe(int dn) {
        for (Pipe p : pipes) {
            if (p.getDn() == dn) {
                return p;
            }
        }
        // ДУ существующей линии может не совпасть с таблицей: берём ближайший не меньший
        for (Pipe p : pipes) {
            if (p.getDn() >= dn) {
                return p;
            }
        }
        return pipes.get(pipes.size() - 1);
    }

    /** Минимальный ДУ по расходу (R-DN-01), или -1, если расход больше максимального. */
    public int minDnForFlow(double flow) {
        for (Pipe p : pipes) {
            if (p.getQMax() >= flow) {
                return p.getDn();
            }
        }
        return -1;
    }

    /** Следующий ДУ по таблице или -1. */
    public int nextDn(int dn) {
        for (Pipe p : pipes) {
            if (p.getDn() > dn) {
                return p.getDn();
            }
        }
        return -1;
    }

    public double width(int dn) {
        return pipe(dn).getWidth();
    }

    public double costPerM(int dn) {
        return pipe(dn).getCostM();
    }

    public double lMax(int dn) {
        return pipe(dn).getLMax();
    }

    public double newChamberCost(int maxDn) {
        for (DnRange r : props.getChambers().getNewCostByMaxDn()) {
            if (maxDn >= r.getFrom() && maxDn <= r.getTo()) {
                return r.getValue();
            }
        }
        // ДУ между диапазонами таблицы: берём диапазон ближайшего большего
        return props.getChambers().getNewCostByMaxDn().stream()
                .filter(r -> r.getFrom() >= maxDn).map(DnRange::getValue).findFirst()
                .orElse(props.getChambers().getNewCostByMaxDn().get(props.getChambers().getNewCostByMaxDn().size() - 1).getValue());
    }

    public double existingTieInCost() {
        return props.getChambers().getExistingTieInCost();
    }

    public int maxConnections() {
        return props.getChambers().getMaxConnections();
    }

    public Restriction restriction(String type) {
        Restriction r = props.getRestrictions().get(type);
        return r != null ? r : props.getUnknownRestriction();
    }

    public boolean isKnownRestriction(String type) {
        return props.getRestrictions().containsKey(type);
    }

    /** d_min для ограничения при ДУ новой трубы. */
    public double offset(Restriction r, int dn) {
        for (DnRange range : r.getOffsetByDn()) {
            if (dn >= range.getFrom() && dn <= range.getTo()) {
                return range.getValue();
            }
        }
        return r.getOffset();
    }

    /** Вклад в S (0,7·C/норм), без длины. */
    public double costScore(double rub) {
        HeatProperties.Score s = props.getScore();
        return s.getWCost() * rub / s.getCostNorm();
    }

    public double lengthScore(double meters) {
        HeatProperties.Score s = props.getScore();
        return s.getWLen() * meters / s.getLenNorm();
    }

    /** Вклад одного метра трассы в S при данном ДУ и коэффициенте K (раздел 6.4 rules.md). */
    public double scorePerMeter(int dn, double k) {
        return costScore(costPerM(dn) * k) + lengthScore(1);
    }

    public double score(double calculatedCost, double length) {
        return costScore(calculatedCost) + lengthScore(length);
    }

    public double unconnectedPenalty(double flow) {
        return props.getScore().getPenaltyBase() + props.getScore().getPenaltyPerTph() * flow;
    }
}
