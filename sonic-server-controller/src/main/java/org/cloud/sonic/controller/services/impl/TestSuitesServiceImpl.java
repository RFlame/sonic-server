/*
 *  Copyright (C) [SonicCloudOrg] Sonic Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.cloud.sonic.controller.services.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.cloud.sonic.common.http.RespEnum;
import org.cloud.sonic.common.http.RespModel;
import org.cloud.sonic.common.tools.BeanTool;
import org.cloud.sonic.controller.mapper.*;
import org.cloud.sonic.controller.models.base.CommentPage;
import org.cloud.sonic.controller.models.base.TypeConverter;
import org.cloud.sonic.controller.models.domain.*;
import org.cloud.sonic.controller.models.dto.*;
import org.cloud.sonic.controller.models.enums.ConditionEnum;
import org.cloud.sonic.controller.models.interfaces.CoverType;
import org.cloud.sonic.controller.models.interfaces.DeviceStatus;
import org.cloud.sonic.controller.models.interfaces.PlatformType;
import org.cloud.sonic.controller.models.interfaces.ResultStatus;
import org.cloud.sonic.controller.services.*;
import org.cloud.sonic.controller.services.impl.base.SonicServiceImpl;
import org.cloud.sonic.controller.tools.BytesTool;
import org.cloud.sonic.controller.transport.TransportWorker;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import javax.websocket.Session;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author ZhouYiXun
 * @des 测试套件逻辑实现
 * @date 2021/8/20 17:51
 */
@Service
public class TestSuitesServiceImpl extends SonicServiceImpl<TestSuitesMapper, TestSuites> implements TestSuitesService, ApplicationContextAware {

    @Autowired
    private TestCasesMapper testCasesMapper;
    @Autowired
    private DevicesMapper devicesMapper;
    @Autowired
    private ResultsService resultsService;
    @Autowired
    private GlobalParamsService globalParamsService;
    @Autowired
    private StepsService stepsService;
    @Autowired
    private PublicStepsService publicStepsService;
    @Autowired
    private TestSuitesTestCasesMapper testSuitesTestCasesMapper;
    @Autowired
    private TestSuitesDevicesMapper testSuitesDevicesMapper;
    @Autowired
    private AgentsService agentsService;
    @Autowired
    private PackagesService packagesService;

    private Map<Integer, CoverHandler> coverHandlerMap;

    private ApplicationContext applicationContext;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RespModel<String> runSuite(int suiteId, String strike) {
        TestSuitesDTO testSuitesDTO;
        // 统计不在线的agent
        List<Integer> offLineAgentIds = new ArrayList<>();
        if (existsById(suiteId)) {
            testSuitesDTO = findById(suiteId);
        } else {
            return new RespModel<>(3001, "suite.deleted");
        }

        if (testSuitesDTO.getTestCases().size() == 0) {
            return new RespModel<>(3002, "suite.empty.cases");
        }

        List<Devices> devicesList = BeanTool.transformFromInBatch(testSuitesDTO.getDevices(), Devices.class);
        for (int i = devicesList.size() - 1; i >= 0; i--) {
            if (devicesList.get(i).getStatus().equals(DeviceStatus.OFFLINE) || devicesList.get(i).getStatus().equals(DeviceStatus.DISCONNECTED)) {
                devicesList.remove(devicesList.get(i));
            }
        }
        if (devicesList.size() == 0) {
            return new RespModel<>(3003, "suite.not.free.device");
        }

        // 初始化部分结果状态信息
        Results results = new Results();
        results.setStatus(ResultStatus.RUNNING);
        results.setSuiteId(suiteId);
        results.setSuiteName(testSuitesDTO.getName());
        results.setStrike(strike);
        if (testSuitesDTO.getCover() == CoverType.CASE) {
            results.setSendMsgCount(testSuitesDTO.getTestCases().size());
        }
        if (testSuitesDTO.getCover() == CoverType.DEVICE) {
            results.setSendMsgCount(testSuitesDTO.getTestCases().size() * testSuitesDTO.getDevices().size());
        }
        results.setReceiveMsgCount(0);
        results.setProjectId(testSuitesDTO.getProjectId());
        resultsService.save(results);

        //组装全局参数为json对象
        List<GlobalParams> globalParamsList = globalParamsService.findAll(testSuitesDTO.getProjectId());

        //将包含|的拆开多个参数并打乱，去掉json对象多参数的字段
        Map<String, List<String>> valueMap = new HashMap<>();
        JSONObject gp = new JSONObject();
        for (GlobalParams g : globalParamsList) {
            if (g.getParamsValue().contains("|")) {
                List<String> shuffle = new ArrayList<>(Arrays.asList(g.getParamsValue().split("\\|")));
                Collections.shuffle(shuffle);
                valueMap.put(g.getParamsKey(), shuffle);
            } else {
                gp.put(g.getParamsKey(), g.getParamsValue());
            }
        }
        JSONObject result = new JSONObject();
        result.put("msg", "suite");
        result.put("pf", testSuitesDTO.getPlatform());
        Map<Integer, List<JSONObject>> agentMap = coverHandlerMap.get(testSuitesDTO.getCover())
                .handlerSuite(testSuitesDTO, devicesList,  valueMap, results);
        for (Integer id : agentMap.keySet()) {
            result.put("cases", agentMap.get(id));
            TransportWorker.send(id, result);
        }
        return new RespModel<>(RespEnum.HANDLE_OK);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RespModel<String> forceStopSuite(int resultId, String strike) {

        Results results = resultsService.findById(resultId);
        // 统计不在线的agent
        List<Integer> offLineAgentIds = new ArrayList<>();
        if (ObjectUtils.isEmpty(results)) {
            return new RespModel<>(3001, "suite.empty.result");
        }
        int suiteId = results.getSuiteId();

        TestSuitesDTO testSuitesDTO;
        if (existsById(suiteId)) {
            testSuitesDTO = findById(suiteId);
        } else {
            return new RespModel<>(3001, "suite.deleted");
        }

        if (testSuitesDTO.getTestCases().size() == 0) {
            return new RespModel<>(3002, "suite.empty.cases");
        }

        List<Devices> devicesList = BeanTool.transformFromInBatch(testSuitesDTO.getDevices(), Devices.class);
        for (int i = devicesList.size() - 1; i >= 0; i--) {
            if (devicesList.get(i).getStatus().equals(DeviceStatus.OFFLINE) || devicesList.get(i).getStatus().equals(DeviceStatus.DISCONNECTED)) {
                devicesList.remove(devicesList.get(i));
            }
        }
        if (devicesList.size() == 0) {
            return new RespModel<>(3003, "suite.can.not.connect.device");
        }

        results.setStatus(ResultStatus.FAIL);
        results.setStrike(strike);
        if (testSuitesDTO.getCover() == CoverType.CASE) {
            results.setSendMsgCount(testSuitesDTO.getTestCases().size());
        }
        if (testSuitesDTO.getCover() == CoverType.DEVICE) {
            results.setSendMsgCount(testSuitesDTO.getTestCases().size() * testSuitesDTO.getDevices().size());
        }
        results.setProjectId(testSuitesDTO.getProjectId());
        resultsService.save(results);

        int deviceIndex = 0;
        if (testSuitesDTO.getCover() == CoverType.CASE) {
            List<JSONObject> suiteDetail = new ArrayList<>();
            Set<Integer> agentIds = new HashSet<>();
            for (TestCasesDTO testCases : testSuitesDTO.getTestCases()) {
                JSONObject suite = new JSONObject();
                suite.put("cid", testCases.getId());
                Devices devices = devicesList.get(deviceIndex);
                // 不要用List.of，它的实现ImmutableCollections无法被序列化
                suite.put("device", new ArrayList<>() {{
                    add(devices);
                }});
                if (deviceIndex == devicesList.size() - 1) {
                    deviceIndex = 0;
                } else {
                    deviceIndex++;
                }
                suite.put("rid", results.getId());
                agentIds.add(devices.getAgentId());
                suiteDetail.add(suite);
            }
            JSONObject result = new JSONObject();
            result.put("msg", "forceStopSuite");
            result.put("pf", testSuitesDTO.getPlatform());
            result.put("cases", suiteDetail);
            for (Integer id : agentIds) {
                TransportWorker.send(id, result);
            }
        }
        if (testSuitesDTO.getCover() == CoverType.DEVICE) {
            List<JSONObject> suiteDetail = new ArrayList<>();
            Set<Integer> agentIds = new HashSet<>();
            for (TestCasesDTO testCases : testSuitesDTO.getTestCases()) {
                JSONObject suite = new JSONObject();
                for (Devices devices : devicesList) {
                    agentIds.add(devices.getAgentId());
                }
                suite.put("cid", testCases.getId());
                suite.put("device", devicesList);
                suite.put("rid", results.getId());
                suiteDetail.add(suite);
            }
            JSONObject result = new JSONObject();
            result.put("msg", "forceStopSuite");
            result.put("pf", testSuitesDTO.getPlatform());
            result.put("cases", suiteDetail);
            for (Integer id : agentIds) {
                TransportWorker.send(id, result);
            }
        }
        return new RespModel<>(RespEnum.HANDLE_OK);
    }


    @Override
    @Transactional
    public TestSuitesDTO findById(int id) {
        if (existsById(id)) {
            TestSuitesDTO testSuitesDTO = baseMapper.selectById(id).convertTo();
            int suiteId = testSuitesDTO.getId();

            // 填充testcase
            List<TestCasesDTO> testCasesDTOList = testCasesMapper.listByTestSuitesId(suiteId)
                    .stream().map(TypeConverter::convertTo).collect(Collectors.toList());
            testSuitesDTO.setTestCases(testCasesDTOList);

            // 填充devices
            List<DevicesDTO> devicesDTOList = devicesMapper.listByTestSuitesId(suiteId)
                    .stream().map(TypeConverter::convertTo).collect(Collectors.toList());
            testSuitesDTO.setDevices(devicesDTOList);

            return testSuitesDTO;
        } else {
            return null;
        }
    }

    /**
     * @param steps
     * @return com.alibaba.fastjson.JSONObject
     * @author ZhouYiXun
     * @des 递归获取步骤
     * @date 2021/8/20 17:50
     */
    @Transactional
    @Override
    public JSONObject getStep(StepsDTO steps) {
        JSONObject step = new JSONObject();
        if (steps.getStepType().equals("install") && steps.getContent().equals("2")) {
            String plat = "unknown";
            if (steps.getPlatform() == PlatformType.ANDROID) {
                plat = "Android";
            }
            if (steps.getPlatform() == PlatformType.IOS) {
                plat = "iOS";
            }
            steps.setText(packagesService.findOne(steps.getProjectId(), steps.getText(), plat));
        }

        if (steps.getStepType().equals("publicStep")) {
            PublicStepsDTO publicStepsDTO = publicStepsService.findById(Integer.parseInt(steps.getText()));
            if (publicStepsDTO != null) {
                JSONArray publicStepsJson = new JSONArray();
                for (StepsDTO pubStep : publicStepsDTO.getSteps()) {
                    publicStepsJson.add(getStep(pubStep));
                }
                step.put("pubSteps", publicStepsJson);
            }
        }

        JSONArray childStepJsonObjs = new JSONArray();
        JSONObject stepsJsonObj = JSON.parseObject(JSON.toJSONString(steps));

        // 如果是条件步骤则遍历子步骤
        if (!ConditionEnum.NONE.getValue().equals(steps.getConditionType())) {
            List<StepsDTO> childSteps = steps.getChildSteps();
            for (StepsDTO childStep : childSteps) {
                // 如果子步骤是公共步骤，则再递归处理；如果不是，则不用处理
                if (childStep.getStepType().equals("publicStep")) {
                    PublicStepsDTO publicStepsDTO = publicStepsService.findById(Integer.parseInt(childStep.getText()));
                    if (publicStepsDTO != null) {
                        JSONArray publicStepsJson = new JSONArray();
                        for (StepsDTO pubStep : publicStepsDTO.getSteps()) {
                            publicStepsJson.add(getStep(pubStep));
                        }
                        JSONObject childStepJsonObj = new JSONObject() {
                            {
                                put("pubSteps", publicStepsJson);
                                put("step", stepsService.handleStep(childStep));
                            }
                        };
                        // 添加转换后的公共步骤
                        childStepJsonObjs.add(childStepJsonObj);
                    }
                } else if (childStep.getStepType().equals("install") && childStep.getContent().equals("2")) {
                    String plat = "unknown";
                    if (childStep.getPlatform() == PlatformType.ANDROID) {
                        plat = "Android";
                    }
                    if (childStep.getPlatform() == PlatformType.IOS) {
                        plat = "iOS";
                    }
                    childStep.setText(packagesService.findOne(childStep.getProjectId(), childStep.getText(), plat));
                    childStepJsonObjs.add(childStep);
                } else {
                    // 如果不是公共步骤，则直接添加
                    childStepJsonObjs.add(childStep);
                }
                stepsJsonObj.put("childSteps", childStepJsonObjs);
            }
            step.put("step", stepsJsonObj);
            return step;
        }

        step.put("step", steps);
        return step;
    }

    @Override
    public boolean delete(int id) {
        return baseMapper.deleteById(id) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveTestSuites(TestSuitesDTO testSuitesDTO) {
        TestSuites testSuites = testSuitesDTO.convertTo();
        save(testSuites);

        Integer suiteId = testSuites.getId();
        testSuitesDTO.setId(suiteId);

        List<TestCasesDTO> testCases = testSuitesDTO.getTestCases();
        List<DevicesDTO> devices = testSuitesDTO.getDevices();

        // 删除旧数据
        testSuitesDevicesMapper.delete(new LambdaQueryWrapper<TestSuitesDevices>()
                .eq(TestSuitesDevices::getTestSuitesId, suiteId)
        );
        testSuitesTestCasesMapper.delete(new LambdaQueryWrapper<TestSuitesTestCases>()
                .eq(TestSuitesTestCases::getTestSuitesId, suiteId)
        );

        // 保存testcase映射
        for (int i = 0; i < testCases.size(); i++) {
            testSuitesTestCasesMapper.insert(
                    new TestSuitesTestCases()
                            .setTestSuitesId(suiteId)
                            .setTestCasesId(testCases.get(i).getId())
                            .setSort(i + 1)
            );
        }

        // 保存devices映射
        for (int i = 0; i < devices.size(); i++) {
            testSuitesDevicesMapper.insert(
                    new TestSuitesDevices()
                            .setTestSuitesId(suiteId)
                            .setDevicesId(devices.get(i).getId())
                            .setSort(i + 1)
            );
        }
    }

    @Override
    @Transactional
    public CommentPage<TestSuitesDTO> findByProjectId(int projectId, String name, Page<TestSuites> pageable) {

        LambdaQueryChainWrapper<TestSuites> lambdaQuery = lambdaQuery();

        if (projectId != 0) {
            lambdaQuery.eq(TestSuites::getProjectId, projectId);
        }
        if (name != null && name.length() > 0) {
            lambdaQuery.like(TestSuites::getName, name);
        }

        lambdaQuery.orderByDesc(TestSuites::getId);
        Page<TestSuites> page = lambdaQuery.page(pageable);

        List<TestSuitesDTO> testSuitesDTOList = page.getRecords()
                // 转换 + 填充 testcase 和 devices
                .stream().map(e -> findById(e.getId())).collect(Collectors.toList());

        return CommentPage.convertFrom(page, testSuitesDTOList);
    }

    @Override
    public List<TestSuitesDTO> findByProjectId(int projectId) {
        return lambdaQuery().eq(TestSuites::getProjectId, projectId)
                .orderByDesc(TestSuites::getId)
                .list()
                // 转换 + 填充 testcase 和 devices
                .stream().map(e -> findById(e.getId())).collect(Collectors.toList());
    }

    @Override
    public boolean deleteByProjectId(int projectId) {
        return baseMapper.delete(new LambdaQueryWrapper<TestSuites>().eq(TestSuites::getProjectId, projectId)) > 0;
    }

    @Override
    public List<TestSuites> listTestSuitesByTestCasesId(int testCasesId) {
        return testSuitesTestCasesMapper.listTestSuitesByTestCasesId(testCasesId);
    }

    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        initCoverHandlerMap();
    }

    private void initCoverHandlerMap() {
        Map<String, CoverHandler> coverHandlerBeans = applicationContext.getBeansOfType(CoverHandler.class);
        coverHandlerMap = new HashMap<>();
        if(coverHandlerBeans != null) {
            for(CoverHandler coverHandler : coverHandlerBeans.values()) {
                coverHandlerMap.put(coverHandler.cover(), coverHandler);
            }
        }
    }

    private JSONObject packageTestCase(Devices devices, TestCasesDTO testCases, Map<Integer,
            List<JSONObject>> stepsMap, JSONObject gp, Results results, StepsService stepsService) {
        JSONObject testCase = new JSONObject();
        List<JSONObject> steps = stepsMap.get(testCases.getId());
        if(steps == null) {
            steps = new ArrayList<>();
            List<StepsDTO> stepsList = stepsService.findByCaseIdOrderBySort(testCases.getId());
            for (StepsDTO s : stepsList) {
                steps.add(getStep(s));
            }
            stepsMap.put(testCases.getId(), steps);
        }
        testCase.put("steps", steps);
        testCase.put("cid", testCases.getId());
        testCase.put("device", new ArrayList<>() {{
            add(devices);
        }});
        testCase.put("gp", gp);
        testCase.put("rid", results.getId());
        return testCase;
    }

    /**
     * 更新全局变量，如果变量为多值以设备维度进行分配
     * 当设备数量大于变量数量时，前面设备按顺序分配变
     * 量，后面设备统一取变量的最后一个值。
     * @param gp
     * @param valueMap
     * @return
     */
    private JSONObject refreshGlobalParams(JSONObject gp, Map<String, List<String>> valueMap) {
        boolean needClone = true;
        for (String k : valueMap.keySet()) {
            if (valueMap.get(k).size() > 0) {
                String v = valueMap.get(k).get(0);
                if(needClone && gp.get(k) != null) {
                    gp = gp.clone();
                    needClone = false;
                }
                gp.put(k, v);
                valueMap.get(k).remove(0);
            } else {
                valueMap.remove(k);
            }
        }
        return gp;
    }

    interface CoverHandler {
        Map<Integer, List<JSONObject>> handlerSuite(TestSuitesDTO testSuitesDTO, List<Devices> devicesList, Map<String,
                List<String>> valueMap, Results results);
        Integer cover();
    }

    /**
     * 用例覆盖处理器
     */
    @Service
    class CaseCoverHandler implements CoverHandler {
        @Autowired
        private StepsService stepsService;

        @Override
        public Map<Integer, List<JSONObject>> handlerSuite(TestSuitesDTO testSuitesDTO,
                                                           List<Devices> devicesList, Map<String, List<String>> valueMap, Results results) {
            Map<Integer, List<JSONObject>> agentMap = new HashMap<>();
            Map<Integer, List<JSONObject>> stepsMap = new HashMap<>();
            JSONObject gp = new JSONObject();
            for (int i = 0; i < devicesList.size(); i ++) {
                Devices devices = devicesList.get(i);
                List<JSONObject> suiteDetail = agentMap.get(devices.getAgentId());
                if(suiteDetail == null) {
                    suiteDetail = new ArrayList<>();
                    agentMap.put(devices.getAgentId(), suiteDetail);
                }
                gp = refreshGlobalParams(gp, valueMap);
                for (int j = i; j < testSuitesDTO.getTestCases().size(); j += devicesList.size()) {
                    TestCasesDTO testCases = testSuitesDTO.getTestCases().get(j);
                    suiteDetail.add(packageTestCase(devices, testCases, stepsMap, gp, results, this.stepsService));
                }
            }
            return agentMap;
        }

        @Override
        public Integer cover() {
            return CoverType.CASE;
        }
    }

    /**
     * 设备覆盖处理器
     */
    @Service
    class DeviceCoverHandler implements CoverHandler {
        @Autowired
        private StepsService stepsService;

        @Override
        public Map<Integer, List<JSONObject>> handlerSuite(TestSuitesDTO testSuitesDTO,
                                                           List<Devices> devicesList, Map<String, List<String>> valueMap, Results results) {
            Map<Integer, List<JSONObject>> agentMap = new HashMap<>();
            Map<Integer, List<JSONObject>> stepsMap = new HashMap<>();
            JSONObject gp = new JSONObject();
            for (Devices devices : devicesList) {
                List<JSONObject> suiteDetail = agentMap.get(devices.getAgentId());
                if(suiteDetail == null) {
                    suiteDetail = new ArrayList<>();
                    agentMap.put(devices.getAgentId(), suiteDetail);
                }
                gp = refreshGlobalParams(gp, valueMap);
                for (TestCasesDTO testCases : testSuitesDTO.getTestCases()) {
                    suiteDetail.add(packageTestCase(devices, testCases, stepsMap, gp, results, this.stepsService));
                }
            }
            return agentMap;
        }

        @Override
        public Integer cover() {
            return CoverType.DEVICE;
        }
    }
}