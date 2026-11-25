package com.waterrepair.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waterrepair.auth.AuthService;
import com.waterrepair.auth.UserAccount;
import com.waterrepair.auth.UserAccountRepository;
import com.waterrepair.construction.ConstructionPermit;
import com.waterrepair.construction.ConstructionReviewService;
import com.waterrepair.construction.PipeProtectionProfile;
import com.waterrepair.construction.RepairWindow;
import com.waterrepair.construction.RoadSegment;
import com.waterrepair.construction.geom.GeoPoint;
import com.waterrepair.crew.Crew;
import com.waterrepair.crew.CrewRepository;
import com.waterrepair.crew.OperationPermit;
import com.waterrepair.crew.OperationPermitRepository;
import com.waterrepair.crew.ToolItem;
import com.waterrepair.crew.ToolItemRepository;
import com.waterrepair.crew.Vehicle;
import com.waterrepair.crew.VehicleRepository;
import com.waterrepair.network.AddressPoint;
import com.waterrepair.network.AddressPointRepository;
import com.waterrepair.network.GeoLandmark;
import com.waterrepair.network.GeoLandmarkRepository;
import com.waterrepair.network.NetworkNode;
import com.waterrepair.network.NetworkNodeRepository;
import com.waterrepair.network.PipeSegment;
import com.waterrepair.network.PipeSegmentRepository;
import com.waterrepair.network.PressureDevice;
import com.waterrepair.network.PressureDeviceRepository;
import com.waterrepair.network.PressureReading;
import com.waterrepair.network.PressureReadingRepository;
import com.waterrepair.network.ValveSpec;
import com.waterrepair.network.ValveSpecRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 载入随项目提供的模拟管网/班组/账号样本；reset 时清空 public 模式全部业务表后重播。
 */
@Service
public class SeedService {

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final AuthService authService;
    private final UserAccountRepository userRepo;
    private final NetworkNodeRepository nodeRepo;
    private final PipeSegmentRepository segmentRepo;
    private final ValveSpecRepository valveRepo;
    private final AddressPointRepository addressRepo;
    private final GeoLandmarkRepository landmarkRepo;
    private final PressureDeviceRepository deviceRepo;
    private final PressureReadingRepository readingRepo;
    private final CrewRepository crewRepo;
    private final VehicleRepository vehicleRepo;
    private final ToolItemRepository toolRepo;
    private final OperationPermitRepository permitRepo;
    private final ConstructionReviewService constructionReviewService;
    private final com.waterrepair.construction.ConstructionPermitRepository constructionPermitRepo;
    private final com.waterrepair.construction.PipeProtectionProfileRepository pipeProfileRepo;
    private final com.waterrepair.construction.RoadSegmentRepository roadRepo;
    private final com.waterrepair.construction.RepairWindowRepository windowRepo;

    public SeedService(ObjectMapper objectMapper, JdbcTemplate jdbcTemplate, AuthService authService,
                       UserAccountRepository userRepo, NetworkNodeRepository nodeRepo,
                       PipeSegmentRepository segmentRepo, ValveSpecRepository valveRepo,
                       AddressPointRepository addressRepo, GeoLandmarkRepository landmarkRepo,
                       PressureDeviceRepository deviceRepo, PressureReadingRepository readingRepo,
                       CrewRepository crewRepo, VehicleRepository vehicleRepo,
                       ToolItemRepository toolRepo, OperationPermitRepository permitRepo,
                       ConstructionReviewService constructionReviewService,
                       com.waterrepair.construction.ConstructionPermitRepository constructionPermitRepo,
                       com.waterrepair.construction.PipeProtectionProfileRepository pipeProfileRepo,
                       com.waterrepair.construction.RoadSegmentRepository roadRepo,
                       com.waterrepair.construction.RepairWindowRepository windowRepo) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
        this.userRepo = userRepo;
        this.nodeRepo = nodeRepo;
        this.segmentRepo = segmentRepo;
        this.valveRepo = valveRepo;
        this.addressRepo = addressRepo;
        this.landmarkRepo = landmarkRepo;
        this.deviceRepo = deviceRepo;
        this.readingRepo = readingRepo;
        this.crewRepo = crewRepo;
        this.vehicleRepo = vehicleRepo;
        this.toolRepo = toolRepo;
        this.permitRepo = permitRepo;
        this.constructionReviewService = constructionReviewService;
        this.constructionPermitRepo = constructionPermitRepo;
        this.pipeProfileRepo = pipeProfileRepo;
        this.roadRepo = roadRepo;
        this.windowRepo = windowRepo;
    }

    @Transactional
    public void seedIfEmpty() {
        if (userRepo.count() > 0) {
            return;
        }
        doSeed();
    }

    @Transactional
    public Map<String, Object> reset() {
        List<String> tables = jdbcTemplate.queryForList(
                "select tablename from pg_tables where schemaname = 'public'", String.class);
        if (!tables.isEmpty()) {
            String joined = tables.stream().map(t -> "public." + t)
                    .reduce((a, b) -> a + "," + b).orElseThrow();
            jdbcTemplate.execute("truncate table " + joined + " restart identity cascade");
        }
        doSeed();
        return counts();
    }

    private void doSeed() {
        SeedDtos.NetworkSeed network = readJson("samples/network.json", SeedDtos.NetworkSeed.class);
        for (SeedDtos.NodeDto n : network.nodes()) {
            nodeRepo.save(new NetworkNode(n.code(), n.name(), n.type(), n.lng(), n.lat()));
        }
        for (SeedDtos.SegmentDto s : network.segments()) {
            segmentRepo.save(new PipeSegment(s.code(), s.name(), s.from(), s.to(),
                    s.pMin(), s.pMax(), s.tolSec()));
        }
        for (SeedDtos.ValveDto v : network.valves()) {
            valveRepo.save(new ValveSpec(v.code(), v.name(), v.nodeCode(),
                    v.qualification(), v.permit()));
        }
        for (SeedDtos.AddressDto a : network.addresses()) {
            addressRepo.save(new AddressPoint(a.code(), a.address(), a.segment(),
                    a.building(), a.lng(), a.lat(), a.aliases()));
        }
        for (SeedDtos.LandmarkDto l : network.landmarks()) {
            landmarkRepo.save(new GeoLandmark(l.code(), l.name(), l.lng(), l.lat(),
                    l.candidateSegments()));
        }
        for (SeedDtos.DeviceDto d : network.devices()) {
            deviceRepo.save(new PressureDevice(d.deviceNo(), d.name(), d.segment()));
        }
        for (SeedDtos.ReadingDto r : network.readings()) {
            readingRepo.save(new PressureReading(r.code(), r.deviceNo(), r.segment(),
                    r.pressureMpa(), r.sampledAt(),
                    r.clockOffsetSeconds() == null ? 0 : r.clockOffsetSeconds(), r.note()));
        }

        SeedDtos.CrewsSeed crews = readJson("samples/crews.json", SeedDtos.CrewsSeed.class);
        for (SeedDtos.VehicleDto v : crews.vehicles()) {
            vehicleRepo.save(new Vehicle(v.code(), v.name(), v.plateNo(),
                    !Boolean.FALSE.equals(v.available()), v.unavailableReason()));
        }
        for (SeedDtos.ToolDto t : crews.tools()) {
            toolRepo.save(new ToolItem(t.code(), t.name(),
                    !Boolean.FALSE.equals(t.available()), t.unavailableReason()));
        }
        for (SeedDtos.PermitDto p : crews.permits()) {
            permitRepo.save(new OperationPermit(p.code(), p.name(), p.valveCode()));
        }
        for (SeedDtos.CrewDto c : crews.crews()) {
            crewRepo.save(new Crew(c.code(), c.name(),
                    LocalTime.parse(c.shiftStart()), LocalTime.parse(c.shiftEnd()),
                    c.contactPhone(), !Boolean.FALSE.equals(c.active()),
                    c.primaryVehicleCode(), c.qualificationCodes(), c.standardToolCodes()));
        }

        SeedDtos.AccountsSeed accounts = readJson("samples/accounts.json", SeedDtos.AccountsSeed.class);
        for (SeedDtos.AccountDto a : accounts.accounts()) {
            userRepo.save(new UserAccount(a.username(), authService.encode(a.password()),
                    a.displayName(), a.role(), a.crewCode()));
        }

        seedConstruction();
    }

    /** 道路施工许可审查样例：P17 浅埋支管（1.6m/±0.8m/保护2m）、封路许可与抢修窗口。 */
    private void seedConstruction() {
        SeedDtos.ConstructionSeed seed = readJson("samples/construction.json",
                SeedDtos.ConstructionSeed.class);
        for (SeedDtos.ConstructionRoadDto r : seed.roads()) {
            roadRepo.save(new RoadSegment(r.code(), r.name(), toPoints(r.centerline())));
        }
        for (SeedDtos.ConstructionPipeDto p : seed.pipes()) {
            pipeProfileRepo.save(new PipeProtectionProfile(p.code(), p.name(), p.segmentCode(),
                    toPoints(p.centerline()), p.surveyVersion(), p.surveyErrorMeters(),
                    p.protectionDistanceMeters(), p.burialDepthMeters(),
                    !Boolean.FALSE.equals(p.importantUser()), p.importantUserNote(),
                    p.updatedReason(), null));
        }
        for (SeedDtos.ConstructionWindowDto w : seed.windows()) {
            windowRepo.save(new RepairWindow(w.code(), w.title(), w.crewCode(),
                    w.windowStart(), w.windowEnd(), w.plannedApproachRoadCodes(),
                    w.status(), w.revision(), w.note()));
        }
        for (SeedDtos.ConstructionPermitDto p : seed.permits()) {
            ConstructionPermit permit = new ConstructionPermit();
            permit.setPermitNo(p.permitNo());
            permit.setContractor(p.contractor());
            permit.setTitle(p.title());
            permit.setPolygon(toPoints(p.polygon()));
            permit.setExcavationDepthMeters(p.excavationDepthMeters());
            permit.setStartDate(java.time.LocalDate.parse(p.startDate()));
            permit.setEndDate(java.time.LocalDate.parse(p.endDate()));
            permit.setClosedRoadCodes(p.closedRoadCodes() == null ? List.of() : p.closedRoadCodes());
            permit.setGeometryVersion(1);
            permit.setStatus("REVIEWING");
            permit.setLastChangeReason("许可送达水务调度，初始录入");
            constructionPermitRepo.save(permit);
            // 播种即按路线 B 物化首批候选冲突关系，与路线 A 共用同一计算引擎
            constructionReviewService.refreshCandidates(p.permitNo());
        }
    }

    private static List<GeoPoint> toPoints(List<List<Double>> coords) {
        List<GeoPoint> points = new java.util.ArrayList<>();
        if (coords != null) {
            for (List<Double> xy : coords) {
                points.add(new GeoPoint(xy.get(0), xy.get(1)));
            }
        }
        return points;
    }

    private <T> T readJson(String path, Class<T> type) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readValue(in, type);
        } catch (Exception ex) {
            throw new IllegalStateException("无法读取样本文件 " + path + ": " + ex.getMessage(), ex);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> counts() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accounts", userRepo.count());
        m.put("nodes", nodeRepo.count());
        m.put("segments", segmentRepo.count());
        m.put("valves", valveRepo.count());
        m.put("addresses", addressRepo.count());
        m.put("landmarks", landmarkRepo.count());
        m.put("devices", deviceRepo.count());
        m.put("readings", readingRepo.count());
        m.put("crews", crewRepo.count());
        m.put("vehicles", vehicleRepo.count());
        m.put("tools", toolRepo.count());
        m.put("permits", permitRepo.count());
        m.put("constructionPermits", constructionPermitRepo.count());
        m.put("pipeProfiles", pipeProfileRepo.count());
        m.put("roads", roadRepo.count());
        m.put("repairWindows", windowRepo.count());
        return m;
    }
}
