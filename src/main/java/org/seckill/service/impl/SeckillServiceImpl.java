package org.seckill.service.impl;

import java.util.Date;
import java.util.List;

import org.seckill.dao.SeckillDao;
import org.seckill.dao.SuccessKilledDao;
import org.seckill.dao.cache.RedisDao;
import org.seckill.dto.Exposer;
import org.seckill.dto.SeckillExecution;
import org.seckill.entity.Seckill;
import org.seckill.entity.SuccessKilled;
import org.seckill.enums.SeckillStateEnum;
import org.seckill.exception.RepeatKillException;
import org.seckill.exception.SeckillCloseException;
import org.seckill.exception.SeckillException;
import org.seckill.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

@Service
public class SeckillServiceImpl implements SeckillService {
	
	private Logger logger = LoggerFactory.getLogger(this.getClass());
	
	//注入Service依赖
	@Autowired
	private SeckillDao seckillDao;
	
	@Autowired
	private RedisDao redisDao;
	
	@Autowired
	private SuccessKilledDao successKilledDao;
	
	//MD5盐值字符串，用户混淆MD5
	private final String slat = "sldjflsjdfwiejfwjefi$&**(&*&";

	public List<Seckill> getSeckillList() {
		return seckillDao.queryAll(0, 4);
	}

	public Seckill getById(long seckillId) {
		return seckillDao.queryById(seckillId);
	}

	public Exposer exportSeckillUrl(long seckillId) {
		//优化点：缓存优化
		//1、访问redis
		Seckill seckill = redisDao.getSeckill(seckillId);
		if(seckill == null){
			//2、访问数据库
			seckill = seckillDao.queryById(seckillId);
			if(seckill == null){
				return new Exposer(false, seckillId);
			}else{
				//3、放入Redis中
				redisDao.putSeckill(seckill);
			}
		}
		
		if(seckill == null){
			return new Exposer(false, seckillId);
		}
		Date startTime = seckill.getStartTime();
		Date endTime = seckill.getEndTime();
		Date nowTime = new Date();//系统当前时间
		if(nowTime.getTime() < startTime.getTime() || nowTime.getTime() > endTime.getTime()){
			return new Exposer(false, seckillId, nowTime.getTime(), startTime.getTime(), endTime.getTime());
		}
		String md5 = getMD5(seckillId);
		return new Exposer(true, md5, seckillId);
	}
	
	private String getMD5(long seckillId){
		String base = seckillId + "/" + slat;
		String md5 = DigestUtils.md5DigestAsHex(base.getBytes());
		return md5;
	}

	@Transactional
	public SeckillExecution executeSeckill(long seckillId, long userPhone, String md5) {
		if(md5 == null || !md5.equals(getMD5(seckillId))){
			throw new SeckillException("seckill data rewrite");
		}
		//执行秒杀逻辑：减库存 + 记录购买行为
		Date nowTime = new Date();
		try {
			//记录购买行为
			int insertCount = successKilledDao.insertSuccessKilled(seckillId, userPhone);
			if(insertCount <= 0){
				//重复秒杀
				throw new RepeatKillException("seckill repeated");
			} else {
				//减库存
				int updateCount = seckillDao.reduceNumber(seckillId, nowTime);
				if(updateCount <= 0){
					//没有更新记录，即秒杀活动结束
					throw new SeckillCloseException("seckill is closed");
				} else {
					//秒杀成功
					SuccessKilled successKilled = successKilledDao.queryByIdWithSeckill(seckillId, userPhone);
					return new SeckillExecution(seckillId, SeckillStateEnum.SUCCESS, successKilled);
				}
			}
		} catch(SeckillCloseException e1) {
			throw e1;
		} catch(RepeatKillException e2){
			throw e2;
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			//所有编译期异常转化为运行期异常
			throw new SeckillException("seckill inner error: " + e.getMessage());
		}
	}
	
	

}












