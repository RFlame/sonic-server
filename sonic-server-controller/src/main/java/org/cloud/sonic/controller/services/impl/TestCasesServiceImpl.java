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

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.cloud.sonic.controller.mapper.*;
import org.cloud.sonic.controller.models.domain.*;
import org.cloud.sonic.controller.models.dto.ElementsDTO;
import org.cloud.sonic.controller.models.dto.PublicStepsAndStepsIdDTO;
import org.cloud.sonic.controller.models.dto.StepsDTO;
import org.cloud.sonic.controller.services.*;
import org.cloud.sonic.controller.services.impl.base.SonicServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author ZhouYiXun
 * @des 测试用例逻辑实现
 * @date 2021/8/20 17:51
 */
@Service
public class TestCasesServiceImpl extends SonicServiceImpl<TestCasesMapper, TestCases> implements TestCasesService {

    @Autowired private StepsService stepsService;
    @Autowired private StepsElementsMapper stepsElementsMapper;
    @Autowired private GlobalParamsService globalParamsService;
    @Autowired private TestSuitesTestCasesMapper testSuitesTestCasesMapper;
    @Autowired private TestSuitesService testSuitesService;
    @Autowired private TestCasesMapper testCasesMapper;
    @Autowired private StepsMapper stepsMapper;
    @Autowired private ElementsService elementsService;
    @Override
    public Page<TestCases> findAll(int projectId, int platform, String name, Page<TestCases> pageable) {

        LambdaQueryChainWrapper<TestCases> lambdaQuery = lambdaQuery();
        if (projectId != 0) {
            lambdaQuery.eq(TestCases::getProjectId, projectId);
        }
        if (platform != 0) {
            lambdaQuery.eq(TestCases::getPlatform, platform);
        }
        if (name != null && name.length() > 0) {
            lambdaQuery.like(TestCases::getName, name);
        }

        return lambdaQuery.orderByDesc(TestCases::getEditTime)
                .page(pageable);
    }

    @Override
    public List<TestCases> findAll(int projectId, int platform) {
        return lambdaQuery().eq(TestCases::getProjectId, projectId)
                .eq(TestCases::getPlatform, platform)
                .orderByDesc(TestCases::getEditTime)
                .list();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean delete(int id) {
        if (existsById(id)) {
            // 删除suite映射关系
            testSuitesTestCasesMapper.delete(
                    new LambdaQueryWrapper<TestSuitesTestCases>()
                            .eq(TestSuitesTestCases::getTestCasesId, id)
            );

            List<StepsDTO> stepsList = stepsService.findByCaseIdOrderBySort(id);
            for (StepsDTO steps : stepsList) {
                steps.setCaseId(0);
                stepsService.updateById(steps.convertTo());
            }
            baseMapper.deleteById(id);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public TestCases findById(int id) {
        return baseMapper.selectById(id);
    }

    @Transactional
    @Override
    public JSONObject findSteps(int id) {

        if (existsById(id)) {
            TestCases runStepCase = baseMapper.selectById(id);
            JSONObject jsonDebug = new JSONObject();
            jsonDebug.put("pf", runStepCase.getPlatform());

            JSONArray array = new JSONArray();
            List<StepsDTO> stepsList = stepsService.findByCaseIdOrderBySort(id);
            for (StepsDTO steps : stepsList) {
                array.add(testSuitesService.getStep(steps));
            }
            jsonDebug.put("steps", array);
            List<GlobalParams> globalParamsList = globalParamsService.findAll(runStepCase.getProjectId());
            JSONObject gp = new JSONObject();
            Map<String, List<String>> valueMap = new HashMap<>();
            for (GlobalParams g : globalParamsList) {
                if (g.getParamsValue().contains("|")) {
                    List<String> shuffle = Arrays.asList(g.getParamsValue().split("\\|"));
//                    Collections.shuffle(shuffle);//不用随机，改成取第一个
                    valueMap.put(g.getParamsKey(), shuffle);
                } else {
                    gp.put(g.getParamsKey(), g.getParamsValue());
                }
            }
            for (String k : valueMap.keySet()) {
                if (valueMap.get(k).size() > 0) {
                    String v = valueMap.get(k).get(0);
                    gp.put(k, v);
                }
            }
            jsonDebug.put("gp", gp);
            return jsonDebug;
        } else {
            return null;
        }
    }

    @Override
    public List<TestCases> findByIdIn(List<Integer> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return new ArrayList<>();
        }
        return listByIds(ids);
    }

    @Override
    public boolean deleteByProjectId(int projectId) {
        return baseMapper.delete(new LambdaQueryWrapper<TestCases>().eq(TestCases::getProjectId, projectId)) > 0;
    }

    @Override
    public List<TestCases> listByPublicStepsId(int publicStepsId) {
        List<Steps> steps = stepsService.lambdaQuery().eq(Steps::getText, publicStepsId).list();
        if (CollectionUtils.isEmpty(steps)) {
            return new ArrayList<>();
        }
        Set<Integer> caseIdSet = steps.stream().map(Steps::getCaseId).collect(Collectors.toSet());
        return lambdaQuery().in(TestCases::getId, caseIdSet).list();
    }

    /**
     * 测试用例的复制
     * 基本原理和公共步骤相同，不需要关联publicStep+step
     * 只需要关联了step+ele。
     * @param oldId  需要复制的id
     * @return  返回成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean copyTestById(int oldId) {
        //插入新的testCase
        TestCases oldTestCases = testCasesMapper.selectById(oldId);
        save(oldTestCases.setId(null).setName(oldTestCases.getName()+"_copy"));

        //查找旧的case Step&&对应的ele
        LambdaQueryWrapper<Steps> queryWrapper = new LambdaQueryWrapper<>();
        List<Steps> oldStepsList = stepsMapper.selectList(
                queryWrapper.eq(Steps::getCaseId, oldId).orderByAsc(Steps::getCaseId));
        List<StepsDTO> stepsDTO = new ArrayList<>();
        for(Steps steps : oldStepsList){
            stepsDTO.add(steps.convertTo());

        }
        List<StepsDTO> stepsCopyDTOS = stepsService.handleSteps(stepsDTO);

        //需要插入的步骤记录
        List<PublicStepsAndStepsIdDTO> needCopySteps = stepsService.stepAndIndex(stepsCopyDTOS);

        //插入新的步骤
        LambdaQueryWrapper<Steps> sort = new LambdaQueryWrapper<>();
        List<Steps> stepsList = stepsMapper.selectList(sort.orderByDesc(Steps::getSort));
        int n = 1;
        for (StepsDTO steps : stepsCopyDTOS) {
            Steps step = steps.convertTo();

            if (step.getParentId() != 0) {
                //如果有关联的父亲步骤， 就计算插入过得父亲ID 写入parentId
                Integer fatherIdIndex = 0;
                Integer idIndex = 0;
                //计算子步骤和父步骤的相对间距
                for (PublicStepsAndStepsIdDTO stepsIdDTO : needCopySteps) {
                    if (stepsIdDTO.getStepsDTO().convertTo().equals(step)) {
                        fatherIdIndex = stepsIdDTO.getIndex();
                    }
                    if (stepsIdDTO.getStepsDTO().convertTo().equals(stepsMapper.selectById(step.getParentId()))) {
                        idIndex = stepsIdDTO.getIndex();
                    }
                }
                step.setId(null).setParentId(fatherIdIndex).setCaseId(oldTestCases.getId()).setSort(stepsList.get(0).getSort() + n);
                stepsMapper.insert(step.setCaseId(oldTestCases.getId()));
                //修改父步骤Id
                step.setParentId(step.getId() - (fatherIdIndex - idIndex));
                stepsMapper.updateById(step);
                n++;
                //关联steps和elId
                if (steps.getElements() != null) {
                    elementsService.newStepBeLinkedEle(steps,step);
                }
                continue;
            }
            step.setId(null).setCaseId(oldTestCases.getId()).setSort(stepsList.get(0).getSort() + n);
            stepsMapper.insert(step);
            //关联steps和elId
            if (steps.getElements() != null) {
                elementsService.newStepBeLinkedEle(steps,step);
            }
            n++;
        }
        return true;
    }
}

